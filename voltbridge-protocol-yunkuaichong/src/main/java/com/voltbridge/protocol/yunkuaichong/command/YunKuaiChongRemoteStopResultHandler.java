package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStopEncoder;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 解析云快充 V1.5 的 0x35 停充结果并发布标准命令结果事件。
 *
 * <p>0x35 不回传请求序列或交易流水，只能用设备+枪号关联最近活动停充命令；其成功仅表示
 * ACKED，真实停止由 0x19 产生的 {@code ChargingEndedEvent} 证明。</p>
 */
@Component
public class YunKuaiChongRemoteStopResultHandler implements YunKuaiChongCommandHandler {

    public static final int REMOTE_STOP_RESULT_COMMAND = 0x35;
    private static final int PAYLOAD_LENGTH = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongRemoteStopResultHandler.class);

    private final YunKuaiChongPendingCommandRegistry pendingCommands;
    private final TimeProvider timeProvider;

    public YunKuaiChongRemoteStopResultHandler(YunKuaiChongPendingCommandRegistry pendingCommands,
                                               TimeProvider timeProvider) {
        this.pendingCommands = pendingCommands;
        this.timeProvider = timeProvider;
    }

    @Override
    public int command() {
        return REMOTE_STOP_RESULT_COMMAND;
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_REMOTE_STOP_RESULT_LENGTH",
                    "YunKuaiChong remote stop result payload must contain 10 bytes");
        }
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, 0, 7));
        String connectorNo = BcdCodec.decode(new byte[]{payload[7]});
        int resultCode = payload[8] & 0xFF;
        int failureCode = payload[9] & 0xFF;
        boolean accepted = resultCode == 0x01;
        DeviceId sessionDevice = context.deviceId().orElseThrow(() ->
                new ProtocolException("YKC_SESSION_NOT_BOUND", "Remote stop result requires an authenticated session"));
        if (!sessionDevice.value().equals(deviceCode)) {
            throw new ProtocolException("YKC_DEVICE_MISMATCH", "Remote stop result device does not match session");
        }

        // 协议缺少唯一响应 ID，此降级关联依赖业务层保证同一交易只有一个活动停充命令。
        String correlationId = YunKuaiChongRemoteStopEncoder.correlationId(deviceCode, connectorNo);
        YunKuaiChongPendingCommandRegistry.Resolution resolution = pendingCommands.resolve(
                YunKuaiChongRemoteStopEncoder.COMMAND_TYPE, correlationId, deviceCode, connectorNo).orElse(null);
        if (resolution == null) {
            LOGGER.warn("Unmatched YunKuaiChong 0x35 result: deviceCode={}, connectorNo={}, result=0x{}",
                    deviceCode, connectorNo, Integer.toHexString(resultCode));
            return ProtocolInboundResult.empty();
        }

        Failure failure = accepted ? Failure.none() : mapFailure(failureCode);
        CommandResultEvent event = new CommandResultEvent(UUID.randomUUID(), sessionDevice,
                resolution.commandId(), correlationId, connectorNo,
                YunKuaiChongRemoteStopEncoder.COMMAND_TYPE,
                accepted ? CommandResultStatus.ACKED : CommandResultStatus.FAILED,
                failure.code(), failure.reason(), resultCode, failureCode,
                resolution.duplicate(), timeProvider.now());
        LOGGER.info("YunKuaiChong 0x35 received: commandId={}, deviceCode={}, connectorNo={}, accepted={}, rawFailureCode={}, duplicate={}",
                resolution.commandId(), deviceCode, connectorNo, accepted, failureCode, resolution.duplicate());
        return ProtocolInboundResult.events(List.of(event));
    }

    private Failure mapFailure(int value) {
        return switch (value) {
            case 0x01 -> new Failure("DEVICE_MISMATCH", "Device code mismatch");
            case 0x02 -> new Failure("NOT_CHARGING", "Connector is not charging");
            case 0x03 -> new Failure("COMMAND_REJECTED", "Device rejected remote stop");
            default -> new Failure("UNKNOWN", "Unknown remote stop failure, reason=0x"
                    + Integer.toHexString(value));
        };
    }

    private record Failure(String code, String reason) {
        private static Failure none() {
            return new Failure(null, null);
        }
    }
}
