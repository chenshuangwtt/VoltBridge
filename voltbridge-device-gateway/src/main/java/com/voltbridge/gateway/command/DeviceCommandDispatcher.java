package com.voltbridge.gateway.command;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.port.DeviceCommandGateway;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.DeviceRouter;
import com.voltbridge.gateway.session.DeviceSession;
import com.voltbridge.gateway.session.DeviceSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionStage;

/**
 * {@link DeviceCommandGateway} 在当前单体中的设备网关实现。
 *
 * <p>主线为“标准命令 -> DeviceRoute -> DeviceSession -> ProtocolAdapter -> TransportSession”。
 * 本类负责路由与边界校验，不解释启停业务规则，也不包含云快充/OCPP 命令分支。</p>
 */
@Component
public class DeviceCommandDispatcher implements DeviceCommandGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceCommandDispatcher.class);

    private final DeviceRouter deviceRouter;
    private final DeviceSessionRegistry sessionRegistry;
    private final ProtocolRegistry protocolRegistry;

    public DeviceCommandDispatcher(DeviceRouter deviceRouter, DeviceSessionRegistry sessionRegistry,
                                   ProtocolRegistry protocolRegistry) {
        this.deviceRouter = deviceRouter;
        this.sessionRegistry = sessionRegistry;
        this.protocolRegistry = protocolRegistry;
    }

    @Override
    public CompletionStage<Void> dispatch(DeviceCommand command) {
        // 设备编码只解析为可序列化路由；路由本身不持有网络连接。
        DeviceRoute route = deviceRouter.route(command.deviceId());
        LOGGER.info("Device route resolved: commandId={}, deviceCode={}, sessionId={}, protocol={}",
                command.commandId(), command.deviceId().value(), route.sessionId().value(), route.protocol());
        // sessionId 定位本进程真实连接，connectionEpoch 防止旧路由误用重连后的会话。
        DeviceSession session = sessionRegistry.find(route.sessionId())
                .orElseThrow(() -> new VoltBridgeException("DEVICE_OFFLINE", "Local device session is unavailable"));
        if (!session.matches(route.deviceId(), route.protocol(), route.connectionEpoch())) {
            throw new VoltBridgeException("DEVICE_OFFLINE", "Device route no longer matches the local session");
        }
        if (!session.transportSession().isOpen()) {
            throw new VoltBridgeException("DEVICE_OFFLINE", "Device transport session is closed");
        }
        LOGGER.info("Device session and protocol selected: commandId={}, deviceCode={}, sessionId={}, protocol={}",
                command.commandId(), command.deviceId().value(), session.sessionId().value(), route.protocol());
        // 协议选择来自认证后的 DeviceRoute，业务命令无需知道最终线协议格式。
        var payload = protocolRegistry.require(route.protocol()).encode(session.protocolContext(route.protocol()), command);
        // CompletionStage 成功仅表示载荷写入连接，业务命令此时只能进入 SENT。
        return session.transportSession().send(payload);
    }
}
