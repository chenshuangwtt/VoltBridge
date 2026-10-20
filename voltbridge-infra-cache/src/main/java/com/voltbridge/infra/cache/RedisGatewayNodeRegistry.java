package com.voltbridge.infra.cache;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.gateway.routing.GatewayNode;
import com.voltbridge.gateway.routing.GatewayNodeRegistry;
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

/** Redis Gateway 节点注册表，节点实例 ID 用作重启后的刷新/删除围栏。 */
@Repository
@ConditionalOnProperty(name = "voltbridge.routing.mode", havingValue = "redis")
public class RedisGatewayNodeRegistry implements GatewayNodeRegistry {
    private static final DefaultRedisScript<Long> REFRESH = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'nodeInstanceId') == ARGV[1] then
                redis.call('HSET', KEYS[1], 'lastHeartbeatAt', ARGV[2], 'expiresAt', ARGV[3])
                redis.call('PEXPIRE', KEYS[1], ARGV[4])
                return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'nodeInstanceId') == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisGatewayNodeRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void register(GatewayNode node) {
        try {
            redis.opsForHash().putAll(key(node.gatewayNodeId()), Map.of(
                    "gatewayNodeId", node.gatewayNodeId(), "nodeInstanceId", node.nodeInstanceId(),
                    "grpcHost", node.grpcHost(), "grpcPort", Integer.toString(node.grpcPort()),
                    "startedAt", node.startedAt().toString(), "lastHeartbeatAt", node.lastHeartbeatAt().toString(),
                    "expiresAt", node.expiresAt().toString()));
            redis.expire(key(node.gatewayNodeId()), Duration.ofMillis(ttlMillis(node.expiresAt())));
        } catch (DataAccessException exception) {
            throw unavailable("register", node.gatewayNodeId(), exception);
        }
    }

    @Override
    public Optional<GatewayNode> find(String gatewayNodeId) {
        try {
            Map<Object, Object> values = redis.opsForHash().entries(key(gatewayNodeId));
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new GatewayNode(value(values, "gatewayNodeId"), value(values, "nodeInstanceId"),
                    value(values, "grpcHost"), Integer.parseInt(value(values, "grpcPort")),
                    Instant.parse(value(values, "startedAt")), Instant.parse(value(values, "lastHeartbeatAt")),
                    Instant.parse(value(values, "expiresAt"))));
        } catch (DataAccessException exception) {
            throw unavailable("lookup", gatewayNodeId, exception);
        }
    }

    @Override
    public boolean refresh(String gatewayNodeId, String nodeInstanceId, Instant lastHeartbeatAt, Instant expiresAt) {
        try {
            Long result = redis.execute(REFRESH, List.of(key(gatewayNodeId)), nodeInstanceId,
                    lastHeartbeatAt.toString(), expiresAt.toString(), Long.toString(ttlMillis(expiresAt)));
            return Long.valueOf(1).equals(result);
        } catch (DataAccessException exception) {
            throw unavailable("refresh", gatewayNodeId, exception);
        }
    }

    @Override
    public boolean removeIfOwned(String gatewayNodeId, String nodeInstanceId) {
        try {
            Long result = redis.execute(REMOVE, List.of(key(gatewayNodeId)), nodeInstanceId);
            return Long.valueOf(1).equals(result);
        } catch (DataAccessException exception) {
            throw unavailable("remove", gatewayNodeId, exception);
        }
    }

    private String key(String gatewayNodeId) {
        return RedisRoutingKeys.gatewayNode(gatewayNodeId);
    }

    private long ttlMillis(Instant expiresAt) {
        return Math.max(1, Duration.between(Instant.now(), expiresAt).toMillis());
    }

    private String value(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new VoltBridgeException("STALE_ROUTE", "Redis Gateway node has invalid metadata");
        }
        return value.toString();
    }

    private VoltBridgeException unavailable(String operation, String nodeId, Exception exception) {
        return new VoltBridgeException("ROUTE_REGISTRY_UNAVAILABLE",
                "Redis Gateway node " + operation + " failed for " + nodeId + ": " + exception.getMessage());
    }
}
