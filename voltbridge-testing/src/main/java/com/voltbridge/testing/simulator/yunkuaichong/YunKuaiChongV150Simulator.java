package com.voltbridge.testing.simulator.yunkuaichong;

import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.testing.simulator.core.ChargingSample;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.core.DeviceSimulator;
import com.voltbridge.testing.simulator.scenario.ScenarioTrace;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * 通过真实 TCP socket 扮演云快充 V1.5 充电桩。
 *
 * <p>此类只生成并验证设备侧帧，平台仍须走自身 TCP 解帧、协议适配、业务用例和路由链路。</p>
 */
public final class YunKuaiChongV150Simulator implements DeviceSimulator {

    private final String host;
    private final int port;
    private final String deviceCode;
    private final int connectorNo;
    private final ScenarioTrace trace = new ScenarioTrace();
    private final YunKuaiChongFrameCodec codec = new YunKuaiChongFrameCodec();
    private int sequence = 1;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private String tradeNo;
    private YunKuaiChongFrame remoteStop;

    public YunKuaiChongV150Simulator(String host, int port, String deviceCode, int connectorNo) {
        this.host = host;
        this.port = port;
        this.deviceCode = deviceCode;
        this.connectorNo = connectorNo;
    }

    @Override public DeviceProtocol protocol() { return DeviceProtocol.YUN_KUAI_CHONG_150; }
    @Override public ScenarioTrace trace() { return trace; }

    @Override
    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(5_000);
        input = socket.getInputStream();
        output = socket.getOutputStream();
        trace.add("TCP connected: " + host + ':' + port);
    }

    @Override
    public void loginOrBoot() throws Exception {
        send(0x01, loginPayload());
        YunKuaiChongFrame ack = receive();
        requireCommand(ack, 0x02, "login ACK");
        trace.add("Login accepted: deviceCode=" + deviceCode);
    }

    @Override
    public void heartbeat() throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(9);
        payload.put(BcdCodec.encode(deviceCode)).put(BcdCodec.encodeByte(connectorNo)).put((byte) 0);
        send(0x03, payload.array());
        YunKuaiChongFrame ack = receive();
        requireCommand(ack, 0x04, "heartbeat ACK");
        trace.add("Heartbeat acknowledged");
    }

    @Override
    public void connectorReady() throws Exception {
        send(0x13, realtimePayload("0".repeat(32), 1, true, null));
        trace.add("Connector ready: connectorNo=" + connectorNo);
    }

    @Override
    public void awaitRemoteStart(java.time.Duration timeout) throws Exception {
        socket.setSoTimeout((int) timeout.toMillis());
        YunKuaiChongFrame command = receive();
        requireCommand(command, 0x34, "RemoteStart 0x34");
        if (!BcdCodec.decode(Arrays.copyOfRange(command.payload(), 16, 23)).equals(deviceCode)) {
            throw new IllegalStateException("RemoteStart device code mismatch");
        }
        if (BcdCodec.decodeByte(command.payload()[23]) != connectorNo) {
            throw new IllegalStateException("RemoteStart connector mismatch");
        }
        tradeNo = BcdCodec.decode(Arrays.copyOfRange(command.payload(), 0, 16));
        trace.add("RemoteStart received: tradeNo=" + tradeNo);
    }

    @Override
    public void acceptRemoteStart() throws Exception {
        byte[] payload = new byte[26];
        System.arraycopy(BcdCodec.encode(tradeNo), 0, payload, 0, 16);
        System.arraycopy(BcdCodec.encode(deviceCode), 0, payload, 16, 7);
        payload[23] = BcdCodec.encodeByte(connectorNo);
        payload[24] = 0x01;
        payload[25] = 0x00;
        send(0x33, payload);
        trace.add("RemoteStart accepted");
    }

    @Override
    public void beginCharging(ChargingSample sample) throws Exception {
        requireTradeNo();
        send(0x13, realtimePayload(tradeNo, 3, true, sample));
        trace.add("Charging started: SOC=" + sample.stateOfCharge() + " energy=" + sample.energyKwh() + "kWh");
    }

    @Override
    public void sendChargingProgress(ChargingSample sample) throws Exception {
        requireTradeNo();
        send(0x13, realtimePayload(tradeNo, 3, true, sample));
        trace.add("Progress: SOC=" + sample.stateOfCharge() + " energy=" + sample.energyKwh() + "kWh");
    }

    @Override
    public void awaitRemoteStop(java.time.Duration timeout) throws Exception {
        socket.setSoTimeout((int) timeout.toMillis());
        remoteStop = receive();
        requireCommand(remoteStop, 0x36, "RemoteStop 0x36");
        trace.add("RemoteStop received");
    }

    @Override
    public void acceptRemoteStop() throws Exception {
        if (remoteStop == null) throw new IllegalStateException("RemoteStop has not been received");
        byte[] payload = new byte[10];
        System.arraycopy(BcdCodec.encode(deviceCode), 0, payload, 0, 7);
        payload[7] = BcdCodec.encodeByte(connectorNo);
        payload[8] = 0x01;
        payload[9] = 0x00;
        send(0x35, payload);
        trace.add("RemoteStop accepted");
    }

    @Override
    public void completeCharging(ChargingSample finalSample, CompletionMode mode, boolean duplicateFinalRecord) throws Exception {
        requireTradeNo();
        send(0x19, endChargePayload(finalSample));
        trace.add("EndCharge sent");
        int recordSequence = sequence;
        byte[] record = codec.encode(recordSequence, 0, 0x3B, transactionRecordPayload(finalSample, mode));
        write(record);
        validateTransactionAck(recordSequence);
        trace.add("Final TransactionRecord acknowledged");
        if (duplicateFinalRecord) {
            write(record);
            validateTransactionAck(recordSequence);
            trace.add("Duplicate TransactionRecord acknowledged");
        }
    }

    @Override
    public void disconnect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            trace.add("TCP disconnected");
        }
    }

    private void validateTransactionAck(int recordSequence) throws Exception {
        YunKuaiChongFrame ack = receive();
        requireCommand(ack, 0x40, "TransactionRecord ACK 0x40");
        if (ack.sequenceNumber() != recordSequence || ack.payload().length < 17
                || !BcdCodec.decode(Arrays.copyOfRange(ack.payload(), 0, 16)).equals(tradeNo)
                || ack.payload()[16] != 0) {
            throw new IllegalStateException("Invalid TransactionRecord ACK");
        }
    }

    private void send(int command, byte[] payload) throws IOException {
        write(codec.encode(sequence++, 0, command, payload));
    }

    private void write(byte[] frame) throws IOException {
        output.write(frame);
        output.flush();
    }

    private YunKuaiChongFrame receive() throws Exception {
        byte[] prefix = input.readNBytes(2);
        if (prefix.length != 2) throw new IOException("YunKuaiChong server closed before frame header");
        int dataLength = prefix[1] & 0xFF;
        byte[] tail = input.readNBytes(dataLength + 2);
        if (tail.length != dataLength + 2) throw new IOException("YunKuaiChong server closed during frame");
        byte[] frame = new byte[dataLength + 4];
        System.arraycopy(prefix, 0, frame, 0, 2);
        System.arraycopy(tail, 0, frame, 2, tail.length);
        return codec.decode(frame);
    }

    private byte[] loginPayload() {
        ByteBuffer payload = ByteBuffer.allocate(30);
        payload.put(BcdCodec.encode(deviceCode));
        payload.put((byte) 1).put((byte) 1).put((byte) 0x15);
        byte[] version = "VB-SIM".getBytes(StandardCharsets.US_ASCII);
        payload.put(version).put(new byte[8 - version.length]);
        payload.put((byte) 1).put(new byte[10]).put((byte) 0);
        return payload.array();
    }

    private byte[] realtimePayload(String currentTradeNo, int status, boolean plugged, ChargingSample sample) {
        BigDecimal voltage = sample == null ? BigDecimal.valueOf(230) : sample.voltage();
        BigDecimal current = sample == null ? BigDecimal.ZERO : sample.current();
        int soc = sample == null ? 0 : sample.stateOfCharge();
        long minutes = sample == null ? 0 : sample.simulatedElapsed().toMinutes();
        long energy = sample == null ? 0 : sample.energyKwh().movePointRight(4).setScale(0, RoundingMode.HALF_UP).longValue();
        ByteBuffer payload = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(currentTradeNo)).put(BcdCodec.encode(deviceCode)).put(BcdCodec.encodeByte(connectorNo));
        payload.put((byte) status).put((byte) 0).put((byte) (plugged ? 1 : 0));
        payload.putShort((short) voltage.movePointRight(1).intValue())
                .putShort((short) current.movePointRight(1).intValue()).put((byte) 0);
        payload.putLong(0L).put((byte) soc).put((byte) soc).putShort((short) minutes).putShort((short) 0);
        payload.putInt((int) energy).putInt(0).putInt((int) energy).putShort((short) 0);
        return payload.array();
    }

    private byte[] endChargePayload(ChargingSample sample) {
        ByteBuffer payload = ByteBuffer.allocate(39).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(tradeNo)).put(BcdCodec.encode(deviceCode)).put(BcdCodec.encodeByte(connectorNo));
        payload.put((byte) sample.stateOfCharge()).putShort((short) 22000).putShort((short) 23500);
        payload.put((byte) 20).put((byte) 35).putShort((short) sample.simulatedElapsed().toMinutes());
        payload.putShort((short) sample.energyKwh().movePointRight(1).intValue()).put(BcdCodec.encode("00000000"));
        return payload.array();
    }

    private byte[] transactionRecordPayload(ChargingSample sample, CompletionMode mode) {
        ByteBuffer payload = ByteBuffer.allocate(158).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(tradeNo)).put(BcdCodec.encode(deviceCode)).put(BcdCodec.encodeByte(connectorNo));
        LocalDateTime start = LocalDateTime.ofInstant(sample.occurredAt().minus(sample.simulatedElapsed()), ZoneId.of("Asia/Shanghai"));
        LocalDateTime end = LocalDateTime.ofInstant(sample.occurredAt(), ZoneId.of("Asia/Shanghai"));
        payload.put(cp56(start)).put(cp56(end));
        long energy = sample.energyKwh().movePointRight(4).setScale(0, RoundingMode.HALF_UP).longValue();
        for (int bucket = 0; bucket < 4; bucket++) {
            payload.putInt(100000).putInt((int) (bucket == 2 ? energy : 0)).putInt(0)
                    .putInt((int) (bucket == 2 ? energy : 0));
        }
        long startMeter = sample.meterKwh().subtract(sample.energyKwh()).movePointRight(4).longValue();
        long endMeter = sample.meterKwh().movePointRight(4).longValue();
        putU40(payload, startMeter);
        putU40(payload, endMeter);
        payload.putInt((int) energy).putInt(0).putInt((int) energy);
        byte[] vin = "VBSIMULATOR0000001".getBytes(StandardCharsets.US_ASCII);
        payload.put(Arrays.copyOf(vin, 17));
        payload.put((byte) 0x01).put(cp56(end));
        payload.put((byte) (mode == CompletionMode.REMOTE_STOP ? 0x40 : 0x41)).putLong(0L);
        return payload.array();
    }

    private byte[] cp56(LocalDateTime value) {
        int millis = value.getSecond() * 1000 + value.getNano() / 1_000_000;
        return new byte[]{(byte) millis, (byte) (millis >>> 8), (byte) value.getMinute(), (byte) value.getHour(),
                (byte) value.getDayOfMonth(), (byte) value.getMonthValue(), (byte) (value.getYear() - 2000)};
    }

    private void putU40(ByteBuffer target, long value) {
        for (int index = 0; index < 5; index++) target.put((byte) (value >>> (index * 8)));
    }

    private void requireCommand(YunKuaiChongFrame frame, int expected, String description) {
        if (frame.command() != expected) {
            throw new IllegalStateException(description + " expected 0x" + Integer.toHexString(expected)
                    + " but received 0x" + Integer.toHexString(frame.command()));
        }
    }

    private void requireTradeNo() {
        if (tradeNo == null) throw new IllegalStateException("RemoteStart has not been received");
    }
}
