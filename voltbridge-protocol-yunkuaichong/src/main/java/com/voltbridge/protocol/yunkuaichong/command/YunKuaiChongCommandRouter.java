package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 云快充上行命令字到处理器的显式注册表。
 *
 * <p>解析后的协议帧在这里按 command 分派，避免适配器形成巨型 switch；处理器负责把协议 DTO
 * 转成标准事件或认证请求，不得直接调用业务仓储。</p>
 */
@Component
public class YunKuaiChongCommandRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(YunKuaiChongCommandRouter.class);
    private final Map<Integer, YunKuaiChongCommandHandler> handlers;

    public YunKuaiChongCommandRouter(List<YunKuaiChongCommandHandler> commandHandlers) {
        Map<Integer, YunKuaiChongCommandHandler> registered = new HashMap<>();
        for (YunKuaiChongCommandHandler handler : commandHandlers) {
            if (registered.putIfAbsent(handler.command(), handler) != null) {
                throw new IllegalStateException("Duplicate YunKuaiChong command handler: 0x"
                        + Integer.toHexString(handler.command()));
            }
        }
        handlers = Map.copyOf(registered);
    }

    public ProtocolInboundResult route(ProtocolContext context, YunKuaiChongFrame frame) {
        YunKuaiChongCommandHandler handler = handlers.get(frame.command());
        if (handler == null) {
            LOGGER.warn("Unsupported YunKuaiChong command: sessionId={}, command=0x{}",
                    context.sessionId(), Integer.toHexString(frame.command()));
            return ProtocolInboundResult.empty();
        }
        LOGGER.debug("YunKuaiChong command resolved: sessionId={}, command=0x{}, handler={}",
                context.sessionId(), Integer.toHexString(frame.command()), handler.getClass().getSimpleName());
        return handler.handle(context, frame);
    }
}
