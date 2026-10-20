package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.*;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.common.spi.ProtocolTransactionStartRequest;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.transport.api.BinaryTransportPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 云快充 0x31 设备主动开充请求。
 *
 * <p>本类仅解析协议字段和构造 0x32 回应。交易准入由网关编排器异步委托给业务层，
 * 因而不会在 TCP EventLoop 中执行数据库或 gRPC 调用。</p>
 */
@Component
public class YunKuaiChongStartChargeHandler implements YunKuaiChongCommandHandler {
    private static final int REQUEST = 0x31, ACK = 0x32, LENGTH = 51;
    private final YunKuaiChongFrameCodec codec;
    private final TimeProvider time;
    public YunKuaiChongStartChargeHandler(YunKuaiChongFrameCodec codec, TimeProvider time) {
        this.codec = codec;
        this.time = time;
    }
    @Override public int command() { return REQUEST; }
    @Override public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        byte[] payload = frame.payload();
        if (payload.length != LENGTH) throw new ProtocolException("YKC_START_CHARGE_LENGTH", "0x31 payload must contain 51 bytes");
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, 0, 7));
        DeviceId device = context.deviceId().orElseThrow(() -> new ProtocolException("YKC_START_NOT_AUTHENTICATED", "0x31 before login"));
        if (!device.value().equals(deviceCode)) throw new ProtocolException("YKC_START_DEVICE_MISMATCH", "0x31 device mismatch");
        int connector = Integer.parseInt(BcdCodec.decode(new byte[]{payload[7]}));
        ChargingStartMethod method = switch (payload[8] & 0xff) { case 1 -> ChargingStartMethod.CARD; case 2 -> ChargingStartMethod.REMOTE; case 3 -> ChargingStartMethod.VIN; default -> ChargingStartMethod.UNKNOWN; };
        long card = ByteBuffer.wrap(payload, 10, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        DeviceTransactionStartRequest request = new DeviceTransactionStartRequest(device, DeviceProtocol.YUN_KUAI_CHONG_150,
                connector, time.now(), BigDecimal.ZERO, method, time.now());
        return ProtocolInboundResult.transactionStart(new ProtocolTransactionStartRequest(request,
                result -> new BinaryTransportPayload(codec.encode(frame.sequenceNumber(), frame.encryptionFlag(), ACK,
                        acknowledgement(deviceCode, connector, card, result)))));
    }

    private byte[] acknowledgement(String deviceCode, int connector, long card, DeviceTransactionStartResult result) {
        byte[] ack = new byte[44];
        // 0x32: 交易流水(16) + 桩(7) + 枪(1) + 逻辑卡(8) + 余额分(4 LE) + 鉴权(1) + 原因(1)。
        copyBcd(ack, 0, String.format("%032d", Math.max(0, result.protocolTransactionId())));
        copyBcd(ack, 16, deviceCode);
        copyBcd(ack, 23, String.format("%02d", connector));
        copyBcd(ack, 24, String.format("%016d", Math.max(card, 0)));
        ByteBuffer.wrap(ack, 32, 12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0)
                .put((byte) (result.accepted() ? 0 : 1))
                .put((byte) (result.accepted() ? 0 : failureCode(result.reason())));
        return ack;
    }

    private int failureCode(String reason) {
        return "CONNECTOR_NOT_FOUND".equals(reason) ? 3 : 1;
    }

    private void copyBcd(byte[] target, int offset, String digits) {
        byte[] encoded = BcdCodec.encode(digits);
        System.arraycopy(encoded, 0, target, offset, encoded.length);
    }
}
