package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceDiagnosticEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 解析云快充 V1.5 0x1B BMS 充电错误帧。
 *
 * <p>参考实现将八个状态字节拆分为 2 bit 的“正常/超时/不可信”状态。本实现保留规范化的
 * BMS 通讯诊断类别，并将逐字段解释保存在 protocolDetail，避免把厂商位图扩散为大量领域枚举。</p>
 */
@Component
public class YunKuaiChongBmsChargingErrorHandler implements YunKuaiChongCommandHandler {
    private static final int PAYLOAD_LENGTH = 32;
    private final TimeProvider timeProvider;

    public YunKuaiChongBmsChargingErrorHandler(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @Override public int command() { return 0x1B; }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_BMS_ERROR_LENGTH", "0x1B payload must contain 32 bytes");
        }
        String transactionId = BcdCodec.decode(Arrays.copyOfRange(payload, 0, 16));
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, 16, 23));
        String connectorNo = BcdCodec.decode(new byte[]{payload[23]});
        DeviceId deviceId = context.deviceId().orElseThrow(() ->
                new ProtocolException("YKC_SESSION_NOT_BOUND", "0x1B requires an authenticated session"));
        if (!deviceId.value().equals(deviceCode)) {
            throw new ProtocolException("YKC_DEVICE_MISMATCH", "0x1B device does not match session");
        }
        String detail = detail(Arrays.copyOfRange(payload, 24, 32));
        return ProtocolInboundResult.events(List.of(new DeviceDiagnosticEvent(UUID.randomUUID(), deviceId, connectorNo,
                "ERROR", "BMS_COMMUNICATION", "YKC_BMS_CHARGING_ERROR", "BMS communication timeout or invalid state",
                transactionId, detail, timeProvider.now())));
    }

    private String detail(byte[] values) {
        return "{\"chargerIdentification00\":\"" + state(values[0] & 0x03)
                + "\",\"chargerIdentificationAA\":\"" + state((values[0] >>> 2) & 0x03)
                + "\",\"chargerTimeSync\":\"" + state(values[1] & 0x03)
                + "\",\"chargerReady\":\"" + state((values[1] >>> 2) & 0x03)
                + "\",\"chargerStatus\":\"" + state(values[2] & 0x03)
                + "\",\"chargerAbort\":\"" + state((values[2] >>> 2) & 0x03)
                + "\",\"chargerStatistics\":\"" + state(values[3] & 0x03)
                + "\",\"bmsVehicleIdentification\":\"" + state(values[4] & 0x03)
                + "\",\"bmsParameters\":\"" + state(values[5] & 0x03)
                + "\",\"bmsReady\":\"" + state((values[5] >>> 2) & 0x03)
                + "\",\"bmsStatus\":\"" + state(values[6] & 0x03)
                + "\",\"bmsRequest\":\"" + state((values[6] >>> 2) & 0x03)
                + "\",\"bmsAbort\":\"" + state((values[6] >>> 4) & 0x03)
                + "\",\"bmsStatistics\":\"" + state(values[7] & 0x03) + "\"}";
    }

    private String state(int value) {
        return switch (value) {
            case 0 -> "NORMAL";
            case 1 -> "TIMEOUT";
            case 2 -> "UNTRUSTED";
            default -> "RESERVED";
        };
    }
}
