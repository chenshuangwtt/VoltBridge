package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准设备命令到云快充编码器的类型注册表。
 *
 * <p>新增命令通过独立 {@link YunKuaiChongCommandEncoder} 扩展；这里不判断业务状态，也不发送网络载荷。</p>
 */
@Component
public class YunKuaiChongOutboundCommandRouter {

    private final Map<Class<?>, YunKuaiChongCommandEncoder<?>> encoders;

    public YunKuaiChongOutboundCommandRouter(List<YunKuaiChongCommandEncoder<?>> commandEncoders) {
        Map<Class<?>, YunKuaiChongCommandEncoder<?>> registered = new HashMap<>();
        for (YunKuaiChongCommandEncoder<?> encoder : commandEncoders) {
            if (registered.putIfAbsent(encoder.commandType(), encoder) != null) {
                throw new IllegalStateException("Duplicate YunKuaiChong command encoder: "
                        + encoder.commandType().getName());
            }
        }
        encoders = Map.copyOf(registered);
    }

    public byte[] encode(ProtocolContext context, DeviceCommand command) {
        YunKuaiChongCommandEncoder<?> encoder = encoders.get(command.getClass());
        if (encoder == null) {
            throw new ProtocolException("YKC_COMMAND_UNSUPPORTED",
                    "Unsupported YunKuaiChong command: " + command.getClass().getSimpleName());
        }
        return encodeTyped(encoder, context, command);
    }

    @SuppressWarnings("unchecked")
    private <T extends DeviceCommand> byte[] encodeTyped(YunKuaiChongCommandEncoder<T> encoder,
                                                          ProtocolContext context,
                                                          DeviceCommand command) {
        return encoder.encode(context, (T) command);
    }
}
