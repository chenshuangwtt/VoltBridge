package com.voltbridge.gateway.protocol;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.spi.ProtocolAdapter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备网关拥有的协议适配器注册表。
 *
 * <p>上行按 transport endpoint 选择解码器，下行按已认证路由中的 {@link DeviceProtocol}
 * 选择编码器。注册在启动期一次完成并拒绝重复键，业务层不参与协议选择。</p>
 */
@Component
public class ProtocolRegistry {

    private final Map<DeviceProtocol, ProtocolAdapter> adapters;
    private final Map<String, ProtocolAdapter> endpointAdapters;

    public ProtocolRegistry(List<ProtocolAdapter> protocolAdapters) {
        // Spring 只负责发现插件；注册表在这里显式建立两种查找维度。
        Map<DeviceProtocol, ProtocolAdapter> registered = new HashMap<>();
        Map<String, ProtocolAdapter> registeredEndpoints = new HashMap<>();
        protocolAdapters.forEach(adapter -> {
            if (registered.putIfAbsent(adapter.protocol(), adapter) != null) {
                throw new IllegalStateException("Duplicate protocol adapter: " + adapter.protocol());
            }
            if (registeredEndpoints.putIfAbsent(adapter.endpointId(), adapter) != null) {
                throw new IllegalStateException("Duplicate protocol endpoint: " + adapter.endpointId());
            }
        });
        this.adapters = Map.copyOf(registered);
        this.endpointAdapters = Map.copyOf(registeredEndpoints);
    }

    public ProtocolAdapter require(DeviceProtocol protocol) {
        ProtocolAdapter adapter = adapters.get(protocol);
        if (adapter == null) {
            throw new VoltBridgeException("PROTOCOL_NOT_SUPPORTED", "No adapter registered for " + protocol);
        }
        return adapter;
    }

    public ProtocolAdapter requireEndpoint(String endpointId) {
        ProtocolAdapter adapter = endpointAdapters.get(endpointId);
        if (adapter == null) {
            throw new VoltBridgeException("PROTOCOL_ENDPOINT_NOT_FOUND", "No adapter registered for endpoint " + endpointId);
        }
        return adapter;
    }
}
