package com.voltbridge.protocol.ocpp16.action;

import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 OCPP action 名称注册上行 CALL 处理器，避免协议入口形成动作分支链。
 *
 * <p>各处理器负责协议 payload 校验和标准事件转换；注册表不访问业务仓储或传输连接。</p>
 */
@Component
public class Ocpp16ActionHandlerRegistry {

    private final Map<String, Ocpp16ActionHandler> handlers;

    public Ocpp16ActionHandlerRegistry(List<Ocpp16ActionHandler> actionHandlers) {
        Map<String, Ocpp16ActionHandler> values = new HashMap<>();
        for (Ocpp16ActionHandler handler : actionHandlers) {
            if (values.putIfAbsent(handler.action(), handler) != null) {
                throw new IllegalStateException("Duplicate OCPP action handler: " + handler.action());
            }
        }
        handlers = Map.copyOf(values);
    }

    public ProtocolInboundResult route(ProtocolContext context, Ocpp16Call call) {
        Ocpp16ActionHandler handler = handlers.get(call.action());
        if (handler == null) {
            throw new Ocpp16Exception(call.uniqueId(), "NotSupported",
                    "Unsupported OCPP action: " + call.action());
        }
        return handler.handle(context, call);
    }
}
