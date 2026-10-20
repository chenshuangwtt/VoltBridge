package com.voltbridge.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.DeviceRouteDirectory;
import com.voltbridge.gateway.session.SessionId;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单体模式设备路由目录，只保存可序列化路由元数据，不保存活动连接对象。
 */
@Repository
@ConditionalOnProperty(name = "voltbridge.routing.mode", havingValue = "local", matchIfMissing = true)
public class CaffeineDeviceRouteDirectory implements DeviceRouteDirectory {

    private final Cache<DeviceId, DeviceRoute> routes = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    @Override
    public void publish(DeviceRoute route) {
        routes.asMap().merge(route.deviceId(), route,
                (current, candidate) -> candidate.authenticatedAt().isBefore(current.authenticatedAt())
                        ? current : candidate);
    }

    @Override
    public DeviceRoute claim(DeviceRoute route) {
        java.util.concurrent.atomic.AtomicReference<DeviceRoute> previous = new java.util.concurrent.atomic.AtomicReference<>();
        routes.asMap().compute(route.deviceId(), (ignored, current) -> {
            previous.set(current);
            return route.authenticatedAt().isBefore(current == null ? route.authenticatedAt() : current.authenticatedAt())
                    ? current : route;
        });
        return previous.get();
    }

    @Override
    public Optional<DeviceRoute> find(DeviceId deviceId) {
        return Optional.ofNullable(routes.getIfPresent(deviceId));
    }

    @Override
    public boolean touch(DeviceId deviceId, SessionId sessionId, long connectionEpoch,
                         Instant lastSeenAt, Instant leaseExpiresAt) {
        AtomicBoolean touched = new AtomicBoolean(false);
        routes.asMap().computeIfPresent(deviceId, (ignored, current) -> {
            if (!current.matches(sessionId, connectionEpoch)) {
                return current;
            }
            touched.set(true);
            return new DeviceRoute(current.deviceId(), current.gatewayNodeId(), current.sessionId(),
                    current.connectionEpoch(), current.protocol(), current.authenticatedAt(),
                    lastSeenAt, leaseExpiresAt);
        });
        return touched.get();
    }

    @Override
    public boolean remove(DeviceId deviceId, SessionId sessionId, long connectionEpoch) {
        DeviceRoute current = routes.getIfPresent(deviceId);
        return current != null
                && current.matches(sessionId, connectionEpoch)
                && routes.asMap().remove(deviceId, current);
    }
}
