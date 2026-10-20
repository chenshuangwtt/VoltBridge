package com.voltbridge.infra.cache;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.GatewayNode;
import com.voltbridge.gateway.session.SessionId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用外部真实 Redis 验证分布式路由的序列化、TTL 与所有权围栏。
 *
 * <p>该测试仅在显式指定 {@code -Dredis.it.enabled=true} 时运行，避免普通 Maven
 * 构建依赖开发者机器上的 Redis。连接信息来自 {@code redis.it.host} 与
 * {@code redis.it.port} 系统属性，默认对应本地开发配置。</p>
 */
@EnabledIfSystemProperty(named = "redis.it.enabled", matches = "true")
class RedisRoutingIntegrationTest {
    private static final Duration ROUTE_TTL = Duration.ofMillis(900);
    private static final Duration NODE_TTL = Duration.ofMillis(900);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final List<String> keysToDelete = new ArrayList<>();

    @BeforeAll
    static void connectRedis() {
        String host = System.getProperty("redis.it.host", "localhost");
        int port = Integer.parseInt(System.getProperty("redis.it.port", "6379"));
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        assertEquals("PONG", redis.getConnectionFactory().getConnection().ping(),
                "真实 Redis 必须可连接；请检查 redis.it.host / redis.it.port");
    }

    @AfterEach
    void deleteOnlyKeysCreatedByThisTest() {
        if (!keysToDelete.isEmpty()) {
            redis.delete(keysToDelete);
        }
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void claimLookupReplacementAndOwnershipFencingUseRealRedis() {
        RedisDeviceRouteDirectory routes = new RedisDeviceRouteDirectory(redis);
        DeviceId deviceId = deviceId();
        String key = RedisRoutingKeys.deviceRoute(deviceId.value());
        keysToDelete.add(key);

        DeviceRoute sessionA = route(deviceId, "gateway-a", "instance-a", "session-a", 101, ROUTE_TTL);
        assertNull(routes.claim(sessionA));
        assertEquals(sessionA, routes.find(deviceId).orElseThrow());
        assertTrue(redis.getExpire(key) > 0, "路由必须带 Redis TTL");

        Map<Object, Object> stored = redis.opsForHash().entries(key);
        assertEquals(deviceId.value(), stored.get("deviceCode"));
        assertEquals("gateway-a", stored.get("gatewayNodeId"));
        assertEquals("instance-a", stored.get("gatewayNodeInstanceId"));
        assertEquals("session-a", stored.get("sessionId"));
        assertEquals("101", stored.get("connectionEpoch"));

        DeviceRoute sessionB = route(deviceId, "gateway-b", "instance-b", "session-b", 202, ROUTE_TTL);
        assertEquals(sessionA, routes.claim(sessionB));
        assertEquals(sessionB, routes.find(deviceId).orElseThrow());

        Instant refreshedAt = Instant.now();
        assertFalse(routes.touch(deviceId, "gateway-a", "instance-a", new SessionId("session-a"), 101,
                refreshedAt, refreshedAt.plus(ROUTE_TTL)), "旧会话不能刷新新路由");
        assertFalse(routes.remove(deviceId, "gateway-a", "instance-a", new SessionId("session-a"), 101),
                "旧会话不能删除新路由");
        assertEquals(sessionB, routes.find(deviceId).orElseThrow());

        assertTrue(routes.remove(deviceId, "gateway-b", "instance-b", new SessionId("session-b"), 202));
        assertTrue(routes.find(deviceId).isEmpty());
    }

    @Test
    void ownerRefreshExtendsRouteAndNodeTtlThenBothExpire() {
        RedisDeviceRouteDirectory routes = new RedisDeviceRouteDirectory(redis);
        RedisGatewayNodeRegistry nodes = new RedisGatewayNodeRegistry(redis);
        DeviceId deviceId = deviceId();
        String nodeId = "gateway-" + UUID.randomUUID();
        String routeKey = RedisRoutingKeys.deviceRoute(deviceId.value());
        String nodeKey = RedisRoutingKeys.gatewayNode(nodeId);
        keysToDelete.add(routeKey);
        keysToDelete.add(nodeKey);

        DeviceRoute route = route(deviceId, nodeId, "instance-1", "session-1", 1, ROUTE_TTL);
        routes.claim(route);
        GatewayNode node = node(nodeId, "instance-1", NODE_TTL);
        nodes.register(node);
        long routeTtlBeforeRefresh = redis.getExpire(routeKey);
        long nodeTtlBeforeRefresh = redis.getExpire(nodeKey);
        assertTrue(routeTtlBeforeRefresh > 0);
        assertTrue(nodeTtlBeforeRefresh > 0);

        sleep(Duration.ofMillis(300));
        Instant refreshedAt = Instant.now();
        assertTrue(routes.touch(deviceId, nodeId, "instance-1", new SessionId("session-1"), 1,
                refreshedAt, refreshedAt.plus(ROUTE_TTL)));
        assertTrue(nodes.refresh(nodeId, "instance-1", refreshedAt, refreshedAt.plus(NODE_TTL)));
        assertTrue(redis.getExpire(routeKey) > routeTtlBeforeRefresh - 200,
                "拥有者刷新必须延长路由 TTL");
        assertTrue(redis.getExpire(nodeKey) > nodeTtlBeforeRefresh - 200,
                "拥有者刷新必须延长节点 TTL");

        await(Duration.ofSeconds(3), () -> routes.find(deviceId).isEmpty());
        await(Duration.ofSeconds(3), () -> nodes.find(nodeId).isEmpty());
    }

    private DeviceId deviceId() {
        return new DeviceId("redis-it-" + UUID.randomUUID());
    }

    private DeviceRoute route(DeviceId deviceId, String nodeId, String instanceId, String sessionId,
                              long epoch, Duration ttl) {
        Instant now = Instant.now();
        return new DeviceRoute(deviceId, nodeId, instanceId, new SessionId(sessionId), epoch,
                DeviceProtocol.YUN_KUAI_CHONG_150, now, now, now.plus(ttl));
    }

    private GatewayNode node(String nodeId, String instanceId, Duration ttl) {
        Instant now = Instant.now();
        return new GatewayNode(nodeId, instanceId, "127.0.0.1", 19090, now, now, now.plus(ttl));
    }

    private void await(Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(25));
        }
        assertTrue(condition.getAsBoolean(), "条件未在 " + timeout + " 内成立");
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待 Redis TTL 时被中断", exception);
        }
    }
}
