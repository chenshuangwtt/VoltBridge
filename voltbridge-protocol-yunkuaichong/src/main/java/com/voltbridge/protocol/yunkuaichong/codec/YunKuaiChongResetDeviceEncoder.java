package com.voltbridge.protocol.yunkuaichong.codec;
import com.voltbridge.device.command.ResetDeviceCommand;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;

/** 将规范重启命令编码为 V1.5 0x92：7 字节桩号 BCD 和 4 字节大端执行方式（1 立即、2 空闲）。 */
@Component
public class YunKuaiChongResetDeviceEncoder implements YunKuaiChongCommandEncoder<ResetDeviceCommand> {
    public static final String COMMAND_TYPE = "RESET_DEVICE";
    private final YunKuaiChongFrameCodec codec;
    private final YunKuaiChongPendingCommandRegistry pending;
    public YunKuaiChongResetDeviceEncoder(YunKuaiChongFrameCodec codec, YunKuaiChongPendingCommandRegistry pending) {
        this.codec = codec; this.pending = pending;
    }
    @Override public Class<ResetDeviceCommand> commandType() { return ResetDeviceCommand.class; }
    @Override public byte[] encode(ProtocolContext context, ResetDeviceCommand command) {
        byte[] payload = new byte[11];
        System.arraycopy(BcdCodec.encode(command.deviceId().value()), 0, payload, 0, 7);
        ByteBuffer.wrap(payload, 7, 4).putInt(command.whenIdle() ? 2 : 1);
        pending.registerLatest(COMMAND_TYPE, "", command.deviceId().value(), "0", command.commandId());
        return codec.encode(context.nextSequence(), 0, 0x92, payload);
    }
}
