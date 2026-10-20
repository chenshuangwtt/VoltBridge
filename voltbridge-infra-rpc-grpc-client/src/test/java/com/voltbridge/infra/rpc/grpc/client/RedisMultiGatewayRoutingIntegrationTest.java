package com.voltbridge.infra.rpc.grpc.client;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.contract.v1.DispatchCommandReply;
import com.voltbridge.contract.v1.DispatchCommandRequest;
import com.voltbridge.contract.v1.DispatchStatus;
import com.voltbridge.contract.v1.GatewayRpcServiceGrpc;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.GatewayNode;
import com.voltbridge.gateway.session.SessionId;
import com.voltbridge.infra.cache.RedisDeviceRouteDirectory;
import com.voltbridge.infra.cache.RedisGatewayNodeRegistry;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 Redis 驱动的双 Gateway 命令路由验证。
 *
 * <p>测试启动两个独立动态端口的 gRPC Gateway 端点，Business 端只通过 Redis 中的
 * 设备路由和节点注册表定位目标。显式开启开关后才连接开发者提供的 Redis 实例。</p>
 */
@EnabledIfSystemProperty(named = "redis.it.enabled", matches = "true")
class RedisMultiGatewayRoutingIntegrationTest {
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final List<Server> servers = new CopyOnWriteArrayList<>();
    private final List<String> keysToDelete = new ArrayList<>();
    private final GatewayGrpcClientRegistry clients = new GatewayGrpcClientRegistry();

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(
                System.getProperty("redis.it.host", "localhost"),
                Integer.parseInt(System.getProperty("redis.it.port", "6379")));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void closeResources() throws InterruptedException {
        clients.close();
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(2, TimeUnit.SECONDS);
        }
        if (!keysToDelete.isEmpty()) {
            redis.delete(keysToDelete);
        }
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void businessResolvesRedisRouteAndSendsBothProtocolsOnlyToGatewayB() throws Exception {
        AtomicInteger gatewayAReceived = new AtomicInteger();
        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayA = server(accepted(gatewayAReceived));
        Server gatewayB = server(accepted(gatewayBReceived));
        RedisDeviceRouteDirectory routes = new RedisDeviceRouteDirectory(redis);
        RedisGatewayNodeRegistry nodes = new RedisGatewayNodeRegistry(redis);
        GatewayNode nodeA = node("gateway-a", "instance-a", gatewayA.getPort(), Duration.ofSeconds(5));
        GatewayNode nodeB = node("gateway-b", "instance-b", gatewayB.getPort(), Duration.ofSeconds(5));
        register(nodes, nodeA);
        register(nodes, nodeB);
        GrpcDeviceCommandGateway business = business(routes, nodes);

        for (DeviceProtocol protocol : List.of(DeviceProtocol.YUN_KUAI_CHONG_150, DeviceProtocol.OCPP_16)) {
            DeviceId deviceId = deviceId();
            trackRoute(deviceId);
            routes.claim(route(deviceId, nodeB, "session-b-" + protocol.name(), 2, protocol, Duration.ofSeconds(5)));
            business.dispatch(command(deviceId)).toCompletableFuture().join();
        }

        assertThat(gatewayAReceived).hasValue(0);
        assertThat(gatewayBReceived).hasValue(2);
    }

    @Test
    void reconnectToGatewayBRejectsLateGatewayARemovalAndRoutesOnlyToB() throws Exception {
        AtomicInteger gatewayAReceived = new AtomicInteger();
        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayA = server(accepted(gatewayAReceived));
        Server gatewayB = server(accepted(gatewayBReceived));
        RedisDeviceRouteDirectory routes = new RedisDeviceRouteDirectory(redis);
        RedisGatewayNodeRegistry nodes = new RedisGatewayNodeRegistry(redis);
        GatewayNode nodeA = node("gateway-a", "instance-a", gatewayA.getPort(), Duration.ofSeconds(5));
        GatewayNode nodeB = node("gateway-b", "instance-b", gatewayB.getPort(), Duration.ofSeconds(5));
        register(nodes, nodeA);
        register(nodes, nodeB);
        DeviceId deviceId = deviceId();
        trackRoute(deviceId);
        DeviceRoute routeA = route(deviceId, nodeA, "session-a", 1, DeviceProtocol.YUN_KUAI_CHONG_150, Duration.ofSeconds(5));
        DeviceRoute routeB = route(deviceId, nodeB, "session-b", 2, DeviceProtocol.YUN_KUAI_CHONG_150, Duration.ofSeconds(5));
        routes.claim(routeA);
        routes.claim(routeB);

        assertThat(routes.remove(deviceId, nodeA.gatewayNodeId(), nodeA.nodeInstanceId(), routeA.sessionId(),
                routeA.connectionEpoch())).isFalse();
        assertThat(routes.find(deviceId)).contains(routeB);
        business(routes, nodes).dispatch(command(deviceId)).toCompletableFuture().join();

        assertThat(gatewayAReceived).hasValue(0);
        assertThat(gatewayBReceived).hasValue(1);
    }

    @Test
    void gatewayNodeExpiryPreventsRoutingUntilDeviceReconnectsToNewGateway() throws Exception {
        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayA = server(accepted(new AtomicInteger()));
        Server gatewayB = server(accepted(gatewayBReceived));
        RedisDeviceRouteDirectory routes = new RedisDeviceRouteDirectory(redis);
        RedisGatewayNodeRegistry nodes = new RedisGatewayNodeRegistry(redis);
        GatewayNode nodeA = node("gateway-a", "instance-a", gatewayA.getPort(), Duration.ofMillis(400));
        GatewayNode nodeB = node("gateway-b", "instance-b", gatewayB.getPort(), Duration.ofSeconds(5));
        register(nodes, nodeA);
        DeviceId deviceId = deviceId();
        trackRoute(deviceId);
        routes.claim(route(deviceId, nodeA, "session-a", 1, DeviceProtocol.OCPP_16, Duration.ofSeconds(3)));
        GrpcDeviceCommandGateway business = business(routes, nodes);

        await(Duration.ofSeconds(2), () -> nodes.find(nodeA.gatewayNodeId()).isEmpty());
        assertThatThrownBy(() -> business.dispatch(command(deviceId)).toCompletableFuture().join())
                .hasCauseInstanceOf(VoltBridgeException.class)
                .hasMessageContaining("Gateway node registration is missing");

        register(nodes, nodeB);
        routes.claim(route(deviceId, nodeB, "session-b", 2, DeviceProtocol.OCPP_16, Duration.ofSeconds(5)));
        business.dispatch(command(deviceId)).toCompletableFuture().join();
        assertThat(gatewayBReceived).hasValue(1);
    }

    private GrpcDeviceCommandGateway business(RedisDeviceRouteDirectory routes, RedisGatewayNodeRegistry nodes) {
        TimeProvider clock = Instant::now;
        return new GrpcDeviceCommandGateway(routes, nodes, clients, clock, Duration.ofSeconds(2));
    }

    private void register(RedisGatewayNodeRegistry nodes, GatewayNode node) {
        nodes.register(node);
        keysToDelete.add("voltbridge:v1:gateway:node:{" + node.gatewayNodeId() + "}");
    }

    private void trackRoute(DeviceId deviceId) {
        keysToDelete.add("voltbridge:v1:route:device:{" + deviceId.value() + "}");
    }

    private Server server(GatewayRpcServiceGrpc.GatewayRpcServiceImplBase service) throws Exception {
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        servers.add(server);
        return server;
    }

    private GatewayRpcServiceGrpc.GatewayRpcServiceImplBase accepted(AtomicInteger received) {
        return new GatewayRpcServiceGrpc.GatewayRpcServiceImplBase() {
            @Override
            public void dispatchCommand(DispatchCommandRequest request, StreamObserver<DispatchCommandReply> observer) {
                received.incrementAndGet();
                observer.onNext(DispatchCommandReply.newBuilder().setStatus(DispatchStatus.DISPATCH_ACCEPTED).build());
                observer.onCompleted();
            }
        };
    }

    private DeviceId deviceId() {
        return new DeviceId("redis-gateway-it-" + UUID.randomUUID());
    }

    private GatewayNode node(String nodeId, String instanceId, int port, Duration ttl) {
        Instant now = Instant.now();
        return new GatewayNode(nodeId + '-' + UUID.randomUUID(), instanceId, "127.0.0.1", port,
                now, now, now.plus(ttl));
    }

    private DeviceRoute route(DeviceId deviceId, GatewayNode node, String sessionId, long epoch,
                              DeviceProtocol protocol, Duration ttl) {
        Instant now = Instant.now();
        return new DeviceRoute(deviceId, node.gatewayNodeId(), node.nodeInstanceId(), new SessionId(sessionId), epoch,
                protocol, now, now, now.plus(ttl));
    }

    private StartChargingCommand command(DeviceId deviceId) {
        return new StartChargingCommand(UUID.randomUUID(), deviceId, "1", BigDecimal.TEN, "logical", "physical",
                "redis-multi-gateway-it", Instant.now());
    }

    private void await(Duration timeout, java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 Redis 节点 TTL 时被中断", exception);
            }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
