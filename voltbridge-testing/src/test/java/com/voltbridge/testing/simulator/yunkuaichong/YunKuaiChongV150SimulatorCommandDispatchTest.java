package com.voltbridge.testing.simulator.yunkuaichong;

import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.testing.simulator.core.ChargingSample;
import com.voltbridge.testing.simulator.core.CompletionMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongV150SimulatorCommandDispatchTest {
    private static final String DEVICE_CODE = "20231212000010";
    private static final String TRADE_NO = "12345678901234567890123456789012";

    @Test
    void idleTimeSyncAndOtherLegalCommandDoNotBlockLaterRemoteStart() throws Exception {
        try (FakeGateway gateway = new FakeGateway()) {
            Future<Boolean> awaitingStart = gateway.executor.submit(
                    () -> gateway.simulator.awaitRemoteStartIfAvailable(Duration.ofSeconds(8)));

            gateway.send(0x36, new byte[0]); // Valid downlink, but there is no active transaction to stop.
            LocalDateTime targetTime = LocalDateTime.of(2026, 11, 6, 12, 34, 56, 789_000_000);
            byte[] timeSync = gateway.timeSyncPayload(targetTime);
            gateway.send(0x56, timeSync);
            YunKuaiChongFrame timeSyncResult = gateway.read();

            assertThat(timeSyncResult.command()).isEqualTo(0x55);
            assertThat(timeSyncResult.payload()).containsExactly(timeSync);
            assertThat(gateway.simulator.simulatedDeviceTime()).isEqualTo(targetTime);
            assertThat(gateway.simulator.isConnected()).isTrue();

            gateway.sendRemoteStart();
            assertThat(awaitingStart.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.simulator.trace().render()).contains("TimeSync received", "RemoteStart received");
        }
    }

    @Test
    void timeSyncDuringChargingKeepsChargingAndRemoteStopCompletesNormally() throws Exception {
        try (FakeGateway gateway = new FakeGateway()) {
            gateway.startCharging();
            Future<?> awaitingStop = gateway.executor.submit(() -> {
                gateway.simulator.awaitRemoteStop(Duration.ofSeconds(8));
                return null;
            });

            byte[] timeSync = gateway.timeSyncPayload(LocalDateTime.of(2026, 11, 6, 12, 35, 0, 0));
            gateway.send(0x56, timeSync);
            assertThat(gateway.read().command()).isEqualTo(0x55);
            assertThat(gateway.simulator.isConnected()).isTrue();
            assertThat(gateway.simulator.isCharging()).isTrue();

            gateway.send(0x36, new byte[0]);
            awaitingStop.get(3, TimeUnit.SECONDS);
            gateway.simulator.acceptRemoteStop();
            assertThat(gateway.read().command()).isEqualTo(0x35);

            Future<?> completing = gateway.executor.submit(() -> {
                try {
                    gateway.simulator.completeCharging(gateway.sample(), CompletionMode.REMOTE_STOP, false);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertThat(gateway.read().command()).isEqualTo(0x19);
            YunKuaiChongFrame record = gateway.read();
            assertThat(record.command()).isEqualTo(0x3B);
            gateway.send(0x40, record.sequenceNumber(), gateway.transactionAckPayload());
            assertThat(gateway.read().command()).isEqualTo(0x13);
            completing.get(3, TimeUnit.SECONDS);

            assertThat(gateway.simulator.isCharging()).isFalse();
            assertThat(gateway.simulator.isConnected()).isTrue();
        }
    }

    @Test
    void idleResetAcknowledgesThenReconnectsAndAcceptsRemoteStart() throws Exception {
        try (FakeGateway gateway = new FakeGateway()) {
            Future<Boolean> awaitingStart = gateway.executor.submit(
                    () -> gateway.simulator.awaitRemoteStartIfAvailable(Duration.ofSeconds(10)));
            gateway.send(0x92, gateway.resetPayload(1));

            YunKuaiChongFrame resetResult = gateway.read();
            assertThat(resetResult.command()).isEqualTo(0x91);
            assertThat(resetResult.payload()).hasSize(8);
            assertThat(resetResult.payload()[7]).isEqualTo((byte) 1);
            assertThat(gateway.serverSocket.getInputStream().read()).isEqualTo(-1);

            gateway.acceptDeviceConnection(false);
            gateway.sendRemoteStart();
            assertThat(awaitingStart.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.simulator.isConnected()).isTrue();
            assertThat(gateway.simulator.trace().render()).contains("Simulator reboot completed");
        }
    }

    @Test
    void whenIdleResetDuringChargingWaitsForTransactionThenReconnects() throws Exception {
        try (FakeGateway gateway = new FakeGateway()) {
            gateway.startCharging();
            Future<?> awaitingStop = gateway.executor.submit(() -> {
                gateway.simulator.awaitRemoteStop(Duration.ofSeconds(8));
                return null;
            });

            gateway.send(0x92, gateway.resetPayload(2));
            YunKuaiChongFrame resetResult = gateway.read();
            assertThat(resetResult.command()).isEqualTo(0x91);
            assertThat(resetResult.payload()[7]).isEqualTo((byte) 1);
            assertThat(gateway.simulator.isConnected()).isTrue();
            assertThat(gateway.simulator.isCharging()).isTrue();

            gateway.send(0x36, new byte[0]);
            awaitingStop.get(3, TimeUnit.SECONDS);
            gateway.simulator.acceptRemoteStop();
            assertThat(gateway.read().command()).isEqualTo(0x35);

            Future<?> completing = gateway.executor.submit(() -> {
                try {
                    gateway.simulator.completeCharging(gateway.sample(), CompletionMode.REMOTE_STOP, false);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertThat(gateway.read().command()).isEqualTo(0x19);
            YunKuaiChongFrame record = gateway.read();
            assertThat(record.command()).isEqualTo(0x3B);
            gateway.send(0x40, record.sequenceNumber(), gateway.transactionAckPayload());
            assertThat(gateway.read().command()).isEqualTo(0x13);
            assertThat(gateway.serverSocket.getInputStream().read()).isEqualTo(-1);

            gateway.acceptDeviceConnection(false);
            completing.get(5, TimeUnit.SECONDS);
            assertThat(gateway.simulator.isConnected()).isTrue();
            assertThat(gateway.simulator.isCharging()).isFalse();
            assertThat(gateway.simulator.trace().render()).contains("Reset deferred until charging completes");
        }
    }

    private static final class FakeGateway implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final YunKuaiChongFrameCodec codec = new YunKuaiChongFrameCodec();
        private final YunKuaiChongV150Simulator simulator;
        private Socket serverSocket;
        private int serverSequence = 100;

        private FakeGateway() throws Exception {
            listener = new ServerSocket(0);
            listener.setSoTimeout(5_000);
            simulator = new YunKuaiChongV150Simulator("127.0.0.1", listener.getLocalPort(), DEVICE_CODE, 1);
            simulator.connect();
            acceptDeviceConnection(true);
        }

        private void startCharging() throws Exception {
            Future<Boolean> awaitingStart = executor.submit(
                    () -> simulator.awaitRemoteStartIfAvailable(Duration.ofSeconds(5)));
            sendRemoteStart();
            assertThat(awaitingStart.get(3, TimeUnit.SECONDS)).isTrue();

            simulator.acceptRemoteStart();
            assertThat(read().command()).isEqualTo(0x33);
            simulator.beginCharging(sample());
            assertThat(read().command()).isEqualTo(0x13);
            assertThat(simulator.isCharging()).isTrue();
        }

        private void acceptDeviceConnection(boolean initialConnection) throws Exception {
            serverSocket = listener.accept();
            Future<?> login = initialConnection ? executor.submit(() -> {
                try {
                    simulator.loginOrBoot();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }) : null;
            YunKuaiChongFrame loginRequest = read();
            assertThat(loginRequest.command()).isEqualTo(0x01);
            send(0x02, loginRequest.sequenceNumber(), new byte[0]);
            if (initialConnection) login.get(3, TimeUnit.SECONDS);

            Future<?> heartbeat = initialConnection ? executor.submit(() -> {
                try {
                    simulator.heartbeat();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }) : null;
            YunKuaiChongFrame heartbeatRequest = read();
            assertThat(heartbeatRequest.command()).isEqualTo(0x03);
            send(0x04, heartbeatRequest.sequenceNumber(), new byte[0]);
            if (initialConnection) heartbeat.get(3, TimeUnit.SECONDS);
            if (initialConnection) simulator.connectorReady();
            assertThat(read().command()).isEqualTo(0x13); // Connector state restored after login.
        }

        private void sendRemoteStart() throws IOException {
            byte[] payload = new byte[24];
            System.arraycopy(BcdCodec.encode(TRADE_NO), 0, payload, 0, 16);
            System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 16, 7);
            payload[23] = BcdCodec.encodeByte(1);
            send(0x34, payload);
        }

        private byte[] timeSyncPayload(LocalDateTime targetTime) {
            byte[] payload = new byte[14];
            System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 0, 7);
            int millisOfMinute = targetTime.getSecond() * 1_000 + targetTime.getNano() / 1_000_000;
            payload[7] = (byte) millisOfMinute;
            payload[8] = (byte) (millisOfMinute >>> 8);
            payload[9] = (byte) targetTime.getMinute();
            payload[10] = (byte) targetTime.getHour();
            payload[11] = (byte) targetTime.getDayOfMonth();
            payload[12] = (byte) targetTime.getMonthValue();
            payload[13] = (byte) (targetTime.getYear() % 100);
            return payload;
        }

        private byte[] resetPayload(int mode) {
            byte[] payload = new byte[11];
            System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 0, 7);
            java.nio.ByteBuffer.wrap(payload, 7, 4).putInt(mode);
            return payload;
        }

        private byte[] transactionAckPayload() {
            byte[] payload = new byte[17];
            System.arraycopy(BcdCodec.encode(TRADE_NO), 0, payload, 0, 16);
            return payload;
        }

        private ChargingSample sample() {
            return new ChargingSample(1, Instant.parse("2026-11-06T04:00:00Z"), Duration.ofMinutes(1), 50,
                    new BigDecimal("2.0000"), new BigDecimal("102.0000"), new BigDecimal("7.2"),
                    new BigDecimal("230.0"), new BigDecimal("31.3"));
        }

        private void send(int command, byte[] payload) throws IOException {
            send(command, serverSequence++, payload);
        }

        private void send(int command, int sequence, byte[] payload) throws IOException {
            OutputStream output = serverSocket.getOutputStream();
            output.write(codec.encode(sequence, 0, command, payload));
            output.flush();
        }

        private YunKuaiChongFrame read() throws IOException {
            InputStream input = serverSocket.getInputStream();
            byte[] prefix = input.readNBytes(2);
            if (prefix.length != 2) throw new IOException("Simulator closed before sending a YKC frame");
            int length = prefix[1] & 0xFF;
            byte[] tail = input.readNBytes(length + 2);
            if (tail.length != length + 2) throw new IOException("Simulator closed during a YKC frame");
            byte[] frame = new byte[length + 4];
            System.arraycopy(prefix, 0, frame, 0, 2);
            System.arraycopy(tail, 0, frame, 2, tail.length);
            return codec.decode(frame);
        }

        @Override
        public void close() throws Exception {
            simulator.disconnect();
            if (serverSocket != null) serverSocket.close();
            listener.close();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
