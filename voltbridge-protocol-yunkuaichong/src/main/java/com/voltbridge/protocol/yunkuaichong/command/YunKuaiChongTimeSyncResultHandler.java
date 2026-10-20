package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTimeSyncEncoder;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 解析 0x55 对时结果；该帧即为最终成功证据，因此直接标记 SUCCEEDED。 */
@Component
public class YunKuaiChongTimeSyncResultHandler implements YunKuaiChongCommandHandler {
    private final YunKuaiChongPendingCommandRegistry pendingCommands;
    private final TimeProvider timeProvider;
    public YunKuaiChongTimeSyncResultHandler(YunKuaiChongPendingCommandRegistry pendingCommands, TimeProvider timeProvider) {
        this.pendingCommands = pendingCommands; this.timeProvider = timeProvider;
    }
    @Override public int command() { return 0x55; }
    @Override public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        if (frame.payload().length != 14) throw new ProtocolException("YKC_TIME_SYNC_RESULT_LENGTH", "0x55 payload must contain 14 bytes");
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(frame.payload(), 0, 7));
        DeviceId deviceId = context.deviceId().orElseThrow(() -> new ProtocolException("YKC_SESSION_NOT_BOUND", "0x55 requires an authenticated session"));
        if (!deviceId.value().equals(deviceCode)) throw new ProtocolException("YKC_DEVICE_MISMATCH", "0x55 device does not match session");
        YunKuaiChongPendingCommandRegistry.Resolution resolution = pendingCommands.resolve(
                YunKuaiChongTimeSyncEncoder.COMMAND_TYPE, "", deviceCode, "0").orElse(null);
        if (resolution == null) return ProtocolInboundResult.empty();
        return ProtocolInboundResult.events(List.of(new CommandResultEvent(UUID.randomUUID(), deviceId, resolution.commandId(),
                "", null, "0", YunKuaiChongTimeSyncEncoder.COMMAND_TYPE, CommandResultStatus.SUCCEEDED,
                null, null, null, null, resolution.duplicate(), timeProvider.now())));
    }
}
