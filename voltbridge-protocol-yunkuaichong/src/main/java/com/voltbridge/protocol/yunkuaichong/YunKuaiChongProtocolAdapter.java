package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolAdapter;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.transport.api.BinaryTransportPayload;
import com.voltbridge.transport.api.TransportPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 云快充 1.5 与统一设备模型之间的协议适配器入口。
 *
 * <p>入站边界：二进制载荷 -> {@link YunKuaiChongFrame} -> 命令处理器 -> 标准事件；
 * 出站边界：标准命令 -> 命令编码器 -> 二进制载荷。该类不访问业务仓储或活动 Netty Channel。</p>
 */
@Component
public class YunKuaiChongProtocolAdapter implements ProtocolAdapter {

    public static final String ENDPOINT_ID = "yunkuaichong-v150-tcp";
    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongProtocolAdapter.class);
    private final YunKuaiChongFrameCodec frameCodec;
    private final YunKuaiChongCommandRouter commandRouter;
    private final YunKuaiChongOutboundCommandRouter outboundCommandRouter;

    public YunKuaiChongProtocolAdapter(YunKuaiChongFrameCodec frameCodec,
                                       YunKuaiChongCommandRouter commandRouter,
                                       YunKuaiChongOutboundCommandRouter outboundCommandRouter) {
        this.frameCodec = frameCodec;
        this.commandRouter = commandRouter;
        this.outboundCommandRouter = outboundCommandRouter;
    }

    @Override
    public String endpointId() {
        return ENDPOINT_ID;
    }

    @Override
    public DeviceProtocol protocol() {
        return DeviceProtocol.YUN_KUAI_CHONG_150;
    }

    @Override
    public ProtocolInboundResult decode(ProtocolContext context, TransportPayload payload) {
        if (!(payload instanceof BinaryTransportPayload binary)) {
            throw new IllegalArgumentException("YunKuaiChong requires binary transport payloads");
        }
        // RAW PROTOCOL：先校验载荷类型，再完成帧头、长度与 CRC 解析。
        byte[] frame = binary.value();
        LOGGER.trace("YunKuaiChong raw frame accepted: sessionId={}, frame={}",
                context.sessionId(), HexCodec.encode(frame));
        YunKuaiChongFrame decoded = frameCodec.decode(frame);
        LOGGER.debug("YunKuaiChong frame decoded: sessionId={}, sequence={}, command=0x{}",
                context.sessionId(), decoded.sequenceNumber(), Integer.toHexString(decoded.command()));
        // PROTOCOL DTO -> CANONICAL EVENT：命令字差异在 router/handler 内终止。
        return commandRouter.route(context, decoded);
    }

    @Override
    public TransportPayload encode(ProtocolContext context, DeviceCommand command) {
        // CANONICAL COMMAND -> PROTOCOL MESSAGE -> TRANSPORT PAYLOAD。
        return new BinaryTransportPayload(outboundCommandRouter.encode(context, command));
    }
}
