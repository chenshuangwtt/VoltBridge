package com.voltbridge.gateway.routing;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.DeviceId;
import org.springframework.stereotype.Component;

/**
 * 下行命令的路由查询服务，只返回仍在租约期内的 {@link DeviceRoute}。
 *
 * <p>它不查找真实连接、不编码协议，也不尝试主动连接设备；缺失或过期统一表现为设备离线。</p>
 */
@Component
public class DeviceRouter {

    private final DeviceRouteDirectory routeDirectory;
    private final TimeProvider timeProvider;

    public DeviceRouter(DeviceRouteDirectory routeDirectory, TimeProvider timeProvider) {
        this.routeDirectory = routeDirectory;
        this.timeProvider = timeProvider;
    }

    public DeviceRoute route(DeviceId deviceId) {
        DeviceRoute route = routeDirectory.find(deviceId)
                .orElseThrow(() -> new VoltBridgeException("DEVICE_OFFLINE", "Device is not connected: " + deviceId.value()));
        // 路由存在不等于连接仍可信，租约阻止长期未上报设备继续接收命令。
        if (!route.leaseExpiresAt().isAfter(timeProvider.now())) {
            throw new VoltBridgeException("DEVICE_OFFLINE", "Device route has expired: " + deviceId.value());
        }
        return route;
    }
}
