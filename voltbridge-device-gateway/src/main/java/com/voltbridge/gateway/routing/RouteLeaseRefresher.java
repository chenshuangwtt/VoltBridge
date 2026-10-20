package com.voltbridge.gateway.routing;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.gateway.session.DeviceSession;
import com.voltbridge.gateway.session.DeviceSessionRegistry;
import com.voltbridge.gateway.session.DeviceSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 主动刷新本进程 ACTIVE 会话的 Redis 路由租约。
 *
 * <p>刷新带节点实例、会话 ID 和 epoch 的条件比较；被替换的旧会话只能收到失败结果，不能重新延长
 * 新路由的 TTL。该任务只在 Redis 分布式模式启用，单体模式不依赖 Redis。</p>
 */
@Component
@ConditionalOnProperty(name = "voltbridge.routing.mode", havingValue = "redis")
public class RouteLeaseRefresher {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLeaseRefresher.class);

    private final DeviceSessionRegistry sessions;
    private final DeviceRouteDirectory routes;
    private final GatewayNodeIdentity nodeIdentity;
    private final TimeProvider timeProvider;
    private final Duration lease;

    public RouteLeaseRefresher(DeviceSessionRegistry sessions, DeviceRouteDirectory routes,
                               GatewayNodeIdentity nodeIdentity, TimeProvider timeProvider,
                               @Value("${voltbridge.gateway.route-lease:PT90S}") Duration lease) {
        this.sessions = sessions;
        this.routes = routes;
        this.nodeIdentity = nodeIdentity;
        this.timeProvider = timeProvider;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${voltbridge.gateway.route-refresh-interval:PT30S}")
    void refreshActiveRoutes() {
        Instant now = timeProvider.now();
        for (DeviceSession session : sessions.sessions()) {
            if (session.state() != DeviceSessionState.ACTIVE) {
                continue;
            }
            DeviceId deviceId = session.deviceId().orElse(null);
            if (deviceId == null) {
                continue;
            }
            try {
                boolean refreshed = routes.touch(deviceId, nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId(),
                        session.sessionId(), session.connectionEpoch(), now, now.plus(lease));
                if (!refreshed) {
                    LOGGER.warn("Route refresh rejected as stale: deviceCode={}, gatewayNodeId={}, nodeInstanceId={}, sessionId={}, epoch={}",
                            deviceId.value(), nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId(),
                            session.sessionId().value(), session.connectionEpoch());
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Route refresh failed: deviceCode={}, gatewayNodeId={}, sessionId={}, reason={}",
                        deviceId.value(), nodeIdentity.gatewayNodeId(), session.sessionId().value(), exception.getMessage());
            }
        }
    }
}
