package com.voltbridge.infra.cache;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.DeviceRouteDirectory;
import com.voltbridge.gateway.session.SessionId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 设备路由目录。
 *
 * <p>路由使用 Hash 的显式字符串字段存储，并通过 Lua 对声明、刷新和删除执行所有权比较；不使用
 * Java 序列化，也不向 Redis 写入 TransportSession、Channel 或 Spring 对象。</p>
 */
@Repository
@ConditionalOnProperty(name = "voltbridge.routing.mode", havingValue = "redis")
public class RedisDeviceRouteDirectory implements DeviceRouteDirectory {
    private static final DefaultRedisScript<List> CLAIM = new DefaultRedisScript<>("""
            local previous = redis.call('HGETALL', KEYS[1])
            redis.call('HSET', KEYS[1],
              'deviceCode', ARGV[1], 'gatewayNodeId', ARGV[2], 'gatewayNodeInstanceId', ARGV[3],
              'sessionId', ARGV[4], 'connectionEpoch', ARGV[5], 'protocol', ARGV[6],
              'authenticatedAt', ARGV[7], 'lastSeenAt', ARGV[8], 'leaseExpiresAt', ARGV[9])
            redis.call('PEXPIRE', KEYS[1], ARGV[10])
            return previous
            """, List.class);
    private static final DefaultRedisScript<Long> REFRESH = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'gatewayNodeId') == ARGV[1]
              and redis.call('HGET', KEYS[1], 'gatewayNodeInstanceId') == ARGV[2]
              and redis.call('HGET', KEYS[1], 'sessionId') == ARGV[3]
              and redis.call('HGET', KEYS[1], 'connectionEpoch') == ARGV[4] then
                redis.call('HSET', KEYS[1], 'lastSeenAt', ARGV[5], 'leaseExpiresAt', ARGV[6])
                redis.call('PEXPIRE', KEYS[1], ARGV[7])
                return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'gatewayNodeId') == ARGV[1]
              and redis.call('HGET', KEYS[1], 'gatewayNodeInstanceId') == ARGV[2]
              and redis.call('HGET', KEYS[1], 'sessionId') == ARGV[3]
              and redis.call('HGET', KEYS[1], 'connectionEpoch') == ARGV[4] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisDeviceRouteDirectory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void publish(DeviceRoute route) {
        claim(route);
    }

    @Override
    public DeviceRoute claim(DeviceRoute route) {
        try {
            List<?> previous = redis.execute(CLAIM, List.of(key(route.deviceId())), args(route));
            return fromHash(flatten(previous)).orElse(null);
        } catch (DataAccessException exception) {
            throw unavailable("claim", route.deviceId().value(), exception);
        }
    }

    @Override
    public Optional<DeviceRoute> find(DeviceId deviceId) {
        try {
            return fromHash(redis.opsForHash().entries(key(deviceId)));
        } catch (DataAccessException exception) {
            throw unavailable("lookup", deviceId.value(), exception);
        }
    }

    @Override
    public boolean touch(DeviceId deviceId, SessionId sessionId, long connectionEpoch,
                         Instant lastSeenAt, Instant leaseExpiresAt) {
        return touch(deviceId, "", "", sessionId, connectionEpoch, lastSeenAt, leaseExpiresAt);
    }

    @Override
    public boolean touch(DeviceId deviceId, String gatewayNodeId, String nodeInstanceId, SessionId sessionId,
                         long connectionEpoch, Instant lastSeenAt, Instant leaseExpiresAt) {
        try {
            Long result = redis.execute(REFRESH, List.of(key(deviceId)), gatewayNodeId, nodeInstanceId,
                    sessionId.value(), Long.toString(connectionEpoch), lastSeenAt.toString(), leaseExpiresAt.toString(),
                    Long.toString(ttlMillis(leaseExpiresAt)));
            return Long.valueOf(1).equals(result);
        } catch (DataAccessException exception) {
            throw unavailable("refresh", deviceId.value(), exception);
        }
    }

    @Override
    public boolean remove(DeviceId deviceId, SessionId sessionId, long connectionEpoch) {
        return remove(deviceId, "", "", sessionId, connectionEpoch);
    }

    @Override
    public boolean remove(DeviceId deviceId, String gatewayNodeId, String nodeInstanceId, SessionId sessionId,
                          long connectionEpoch) {
        try {
            Long result = redis.execute(REMOVE, List.of(key(deviceId)), gatewayNodeId, nodeInstanceId,
                    sessionId.value(), Long.toString(connectionEpoch));
            return Long.valueOf(1).equals(result);
        } catch (DataAccessException exception) {
            throw unavailable("remove", deviceId.value(), exception);
        }
    }

    private Object[] args(DeviceRoute route) {
        return new Object[]{route.deviceId().value(), route.gatewayNodeId(), route.gatewayNodeInstanceId(),
                route.sessionId().value(), Long.toString(route.connectionEpoch()), route.protocol().name(),
                route.authenticatedAt().toString(), route.lastSeenAt().toString(), route.leaseExpiresAt().toString(),
                Long.toString(ttlMillis(route.leaseExpiresAt()))};
    }

    private String key(DeviceId deviceId) {
        return RedisRoutingKeys.deviceRoute(deviceId.value());
    }

    private long ttlMillis(Instant expiresAt) {
        return Math.max(1, Duration.between(Instant.now(), expiresAt).toMillis());
    }

    private Optional<DeviceRoute> fromHash(Map<?, ?> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DeviceRoute(new DeviceId(value(values, "deviceCode")), value(values, "gatewayNodeId"),
                    value(values, "gatewayNodeInstanceId"), new SessionId(value(values, "sessionId")),
                    Long.parseLong(value(values, "connectionEpoch")), DeviceProtocol.valueOf(value(values, "protocol")),
                    Instant.parse(value(values, "authenticatedAt")), Instant.parse(value(values, "lastSeenAt")),
                    Instant.parse(value(values, "leaseExpiresAt"))));
        } catch (RuntimeException exception) {
            throw new VoltBridgeException("STALE_ROUTE", "Redis device route has invalid metadata");
        }
    }

    private Map<String, String> flatten(List<?> fields) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        for (int index = 0; fields != null && index + 1 < fields.size(); index += 2) {
            values.put(String.valueOf(fields.get(index)), String.valueOf(fields.get(index + 1)));
        }
        return values;
    }

    private String value(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing Redis route field " + key);
        }
        return value.toString();
    }

    private VoltBridgeException unavailable(String operation, String deviceCode, Exception exception) {
        return new VoltBridgeException("ROUTE_REGISTRY_UNAVAILABLE",
                "Redis route " + operation + " failed for device " + deviceCode + ": " + exception.getMessage());
    }
}
