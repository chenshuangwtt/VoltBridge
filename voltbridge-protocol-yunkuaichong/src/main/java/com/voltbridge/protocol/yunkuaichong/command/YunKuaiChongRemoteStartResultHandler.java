package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 将云快充 0x33 远程启动结果映射为标准 {@link CommandResultEvent}。
 *
 * <p>0x33 中的 tradeNo 属于线协议关联键：先解析到平台 {@code DeviceCommand.id}，再发布 ACKED/FAILED；
 * 接受结果不代表已经开始充电，SUCCEEDED 仍由状态或进度证据推进。</p>
 */
@Component
public class YunKuaiChongRemoteStartResultHandler implements YunKuaiChongCommandHandler {

    public static final int REMOTE_START_RESULT_COMMAND = 0x33;
    private static final int PAYLOAD_LENGTH = 26;
    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongRemoteStartResultHandler.class);

    private final YunKuaiChongPendingCommandRegistry pendingCommands;
    private final TimeProvider timeProvider;

    public YunKuaiChongRemoteStartResultHandler(YunKuaiChongPendingCommandRegistry pendingCommands,
                                                TimeProvider timeProvider) {
        this.pendingCommands = pendingCommands;
        this.timeProvider = timeProvider;
    }

    @Override
    public int command() {
        return REMOTE_START_RESULT_COMMAND;
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_REMOTE_START_RESULT_LENGTH",
                    "YunKuaiChong remote start result payload must contain 26 bytes");
        }
        String correlationId = BcdCodec.decode(Arrays.copyOfRange(payload, 0, 16));
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, 16, 23));
        String connectorNo = BcdCodec.decode(new byte[]{payload[23]});
        boolean accepted = (payload[24] & 0xFF) == 0x01;
        int failureReason = payload[25] & 0xFF;
        DeviceId sessionDevice = context.deviceId().orElseThrow(() ->
                new ProtocolException("YKC_SESSION_NOT_BOUND", "Remote start result requires an authenticated session"));
        if (!sessionDevice.value().equals(deviceCode)) {
            throw new ProtocolException("YKC_DEVICE_MISMATCH", "Remote start result device does not match session");
        }

        // tradeNo + device + connector 只用于找回平台 commandId，不能替代业务交易 ID。
        YunKuaiChongPendingCommandRegistry.Resolution resolution = pendingCommands
                .resolve(correlationId, deviceCode, connectorNo)
                .orElse(null);
        if (resolution == null) {
            LOGGER.warn("Unmatched YunKuaiChong 0x33 result: deviceCode={}, connectorNo={}, correlationId={}",
                    deviceCode, connectorNo, correlationId);
            return ProtocolInboundResult.empty();
        }
        Failure failure = accepted ? Failure.none() : mapFailure(failureReason);
        CommandResultEvent event = new CommandResultEvent(UUID.randomUUID(), sessionDevice,
                resolution.commandId(), correlationId, connectorNo, "START_CHARGING",
                accepted ? CommandResultStatus.ACKED : CommandResultStatus.FAILED,
                failure.code(), failure.reason(), payload[24] & 0xFF, failureReason,
                resolution.duplicate(), timeProvider.now());
        LOGGER.info("YunKuaiChong 0x33 received: commandId={}, deviceCode={}, connectorNo={}, accepted={}, duplicate={}",
                resolution.commandId(), deviceCode, connectorNo, accepted, resolution.duplicate());
        return ProtocolInboundResult.events(List.of(event));
    }

    private Failure mapFailure(int value) {
        return switch (value) {
            case 0x01 -> new Failure("DEVICE_CODE_MISMATCH", "Device code mismatch");
            case 0x02 -> new Failure("CONNECTOR_BUSY", "Connector is already charging");
            case 0x03 -> new Failure("DEVICE_FAULT", "Device fault");
            case 0x04 -> new Failure("DEVICE_OFFLINE", "Device reported offline");
            case 0x05 -> new Failure("CONNECTOR_NOT_PLUGGED", "Connector is not plugged in");
            default -> new Failure("YKC_REMOTE_START_REJECTED", "Remote start rejected, reason=0x"
                    + Integer.toHexString(value));
        };
    }

    private record Failure(String code, String reason) {
        private static Failure none() {
            return new Failure(null, null);
        }
    }
}
