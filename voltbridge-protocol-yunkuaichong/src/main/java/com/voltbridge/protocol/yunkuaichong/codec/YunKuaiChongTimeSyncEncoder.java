package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.device.command.SynchronizeTimeCommand;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 将规范对时命令编码为云快充 V1.5 的 0x56 帧。 */
@Component
public class YunKuaiChongTimeSyncEncoder implements YunKuaiChongCommandEncoder<SynchronizeTimeCommand> {
    public static final String COMMAND_TYPE = "SYNCHRONIZE_TIME";
    private final YunKuaiChongFrameCodec frameCodec;
    private final YunKuaiChongPendingCommandRegistry pendingCommands;
    private final ZoneId timeZone;

    public YunKuaiChongTimeSyncEncoder(YunKuaiChongFrameCodec frameCodec,
                                       YunKuaiChongPendingCommandRegistry pendingCommands,
                                       @Value("${voltbridge.protocol.yunkuaichong.time-zone:Asia/Shanghai}") String timeZone) {
        this.frameCodec = frameCodec;
        this.pendingCommands = pendingCommands;
        this.timeZone = ZoneId.of(timeZone);
    }

    @Override public Class<SynchronizeTimeCommand> commandType() { return SynchronizeTimeCommand.class; }

    @Override
    public byte[] encode(ProtocolContext context, SynchronizeTimeCommand command) {
        String sessionDevice = context.deviceId().orElseThrow(() ->
                new ProtocolException("YKC_SESSION_NOT_BOUND", "Time sync requires an authenticated session")).value();
        if (!sessionDevice.equals(command.deviceId().value())) {
            throw new ProtocolException("YKC_DEVICE_MISMATCH", "Time sync device does not match session");
        }
        byte[] body = new byte[14];
        System.arraycopy(BcdCodec.encode(command.deviceId().value()), 0, body, 0, 7);
        System.arraycopy(cp56(command.targetTime()), 0, body, 7, 7);
        pendingCommands.registerLatest(COMMAND_TYPE, "", command.deviceId().value(), "0", command.commandId());
        return frameCodec.encode(context.nextSequence(), 0, 0x56, body);
    }

    byte[] cp56(Instant instant) {
        ZonedDateTime value = instant.atZone(timeZone);
        int milliseconds = value.getSecond() * 1000 + value.getNano() / 1_000_000;
        return new byte[]{(byte) milliseconds, (byte) (milliseconds >>> 8), (byte) value.getMinute(),
                (byte) value.getHour(), (byte) value.getDayOfMonth(), (byte) value.getMonthValue(),
                (byte) (value.getYear() % 100)};
    }
}
