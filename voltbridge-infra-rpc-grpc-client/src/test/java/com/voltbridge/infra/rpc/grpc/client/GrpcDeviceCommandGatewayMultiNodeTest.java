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
import com.voltbridge.gateway.routing.DeviceRouteDirectory;
import com.voltbridge.gateway.routing.GatewayNode;
import com.voltbridge.gateway.routing.GatewayNodeRegistry;
import com.voltbridge.gateway.session.SessionId;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多 Gateway 动态 gRPC 路由的协议无关集成测试。 */
class GrpcDeviceCommandGatewayMultiNodeTest {
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private final List<Server> servers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final GatewayGrpcClientRegistry clients = new GatewayGrpcClientRegistry();

    @AfterEach
    void closeServers() {
        clients.close();
        servers.forEach(Server::shutdownNow);
    }

    @Test
    void routesYunKuaiChongAndOcppCommandsToOwningGatewayOnly() throws Exception {
        AtomicInteger gatewayAReceived = new AtomicInteger();
        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayA = server(accepted(gatewayAReceived));
        Server gatewayB = server(accepted(gatewayBReceived));
        InMemoryRoutes routes = new InMemoryRoutes();
        InMemoryNodes nodes = new InMemoryNodes();
        nodes.put(node("gateway-a", "instance-a", gatewayA.getPort()));
        nodes.put(node("gateway-b", "instance-b", gatewayB.getPort()));
        GrpcDeviceCommandGateway gateway = gateway(routes, nodes);

        for (DeviceProtocol protocol : List.of(DeviceProtocol.YUN_KUAI_CHONG_150, DeviceProtocol.OCPP_16)) {
            DeviceId device = new DeviceId("DEVICE-" + protocol.name());
            routes.claim(route(device, "gateway-b", "instance-b", "session-b-" + protocol.name(), 2, protocol));
            gateway.dispatch(command(device)).toCompletableFuture().join();
        }

        assertThat(gatewayAReceived).hasValue(0);
        assertThat(gatewayBReceived).hasValue(2);
    }

    @Test
    void lateDisconnectCannotDeleteNewRouteAndBusinessUsesNewGateway() throws Exception {
        AtomicInteger gatewayAReceived = new AtomicInteger();
        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayA = server(accepted(gatewayAReceived));
        Server gatewayB = server(accepted(gatewayBReceived));
        DeviceId device = new DeviceId("RECONNECT-DEVICE");
        InMemoryRoutes routes = new InMemoryRoutes();
        DeviceRoute oldRoute = route(device, "gateway-a", "instance-a", "session-a", 1, DeviceProtocol.YUN_KUAI_CHONG_150);
        DeviceRoute newRoute = route(device, "gateway-b", "instance-b", "session-b", 2, DeviceProtocol.YUN_KUAI_CHONG_150);
        routes.claim(oldRoute);
        routes.claim(newRoute);

        assertThat(routes.remove(device, "gateway-a", "instance-a", oldRoute.sessionId(), oldRoute.connectionEpoch())).isFalse();
        assertThat(routes.find(device)).contains(newRoute);

        InMemoryNodes nodes = new InMemoryNodes();
        nodes.put(node("gateway-a", "instance-a", gatewayA.getPort()));
        nodes.put(node("gateway-b", "instance-b", gatewayB.getPort()));
        gateway(routes, nodes).dispatch(command(device)).toCompletableFuture().join();

        assertThat(gatewayAReceived).hasValue(0);
        assertThat(gatewayBReceived).hasValue(1);
    }

    @Test
    void retriesOnceAfterStaleGatewayReportsMissingSessionThenRecoversOnNewOwner() throws Exception {
        DeviceId device = new DeviceId("STALE-DEVICE");
        InMemoryRoutes routes = new InMemoryRoutes();
        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayB = server(accepted(gatewayBReceived));
        DeviceRoute newRoute = route(device, "gateway-b", "instance-b", "session-b", 2, DeviceProtocol.OCPP_16);
        Server gatewayA = server(new GatewayRpcServiceGrpc.GatewayRpcServiceImplBase() {
            @Override
            public void dispatchCommand(DispatchCommandRequest request, StreamObserver<DispatchCommandReply> observer) {
                routes.claim(newRoute);
                observer.onNext(DispatchCommandReply.newBuilder().setStatus(DispatchStatus.DISPATCH_SESSION_NOT_FOUND).build());
                observer.onCompleted();
            }
        });
        routes.claim(route(device, "gateway-a", "instance-a", "session-a", 1, DeviceProtocol.OCPP_16));
        InMemoryNodes nodes = new InMemoryNodes();
        nodes.put(node("gateway-a", "instance-a", gatewayA.getPort()));
        nodes.put(node("gateway-b", "instance-b", gatewayB.getPort()));

        gateway(routes, nodes).dispatch(command(device)).toCompletableFuture().join();

        assertThat(gatewayBReceived).hasValue(1);
    }

    @Test
    void expiredOrMissingNodeRouteFailsExplicitlyUntilDeviceReconnects() throws Exception {
        DeviceId device = new DeviceId("CRASHED-GATEWAY-DEVICE");
        InMemoryRoutes routes = new InMemoryRoutes();
        InMemoryNodes nodes = new InMemoryNodes();
        routes.claim(new DeviceRoute(device, "gateway-a", "instance-a", new SessionId("session-a"), 1,
                DeviceProtocol.YUN_KUAI_CHONG_150, NOW.minusSeconds(120), NOW.minusSeconds(120), NOW.minusSeconds(1)));
        GrpcDeviceCommandGateway gateway = gateway(routes, nodes);

        assertThatThrownBy(() -> gateway.dispatch(command(device)).toCompletableFuture().join())
                .hasCauseInstanceOf(VoltBridgeException.class)
                .hasMessageContaining("lease has expired");

        AtomicInteger gatewayBReceived = new AtomicInteger();
        Server gatewayB = server(accepted(gatewayBReceived));
        routes.claim(route(device, "gateway-b", "instance-b", "session-b", 2, DeviceProtocol.YUN_KUAI_CHONG_150));
        nodes.put(node("gateway-b", "instance-b", gatewayB.getPort()));
        gateway.dispatch(command(device)).toCompletableFuture().join();
        assertThat(gatewayBReceived).hasValue(1);
    }

    private GrpcDeviceCommandGateway gateway(InMemoryRoutes routes, InMemoryNodes nodes) {
        TimeProvider clock = () -> NOW;
        return new GrpcDeviceCommandGateway(routes, nodes, clients, clock, Duration.ofSeconds(2));
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

    private GatewayNode node(String nodeId, String instanceId, int port) {
        return new GatewayNode(nodeId, instanceId, "127.0.0.1", port, NOW, NOW, NOW.plusSeconds(600));
    }

    private DeviceRoute route(DeviceId device, String nodeId, String instanceId, String sessionId,
                              long epoch, DeviceProtocol protocol) {
        return new DeviceRoute(device, nodeId, instanceId, new SessionId(sessionId), epoch, protocol,
                NOW, NOW, NOW.plusSeconds(600));
    }

    private StartChargingCommand command(DeviceId device) {
        return new StartChargingCommand(UUID.randomUUID(), device, "1", BigDecimal.TEN, "logical", "physical",
                "multi-gateway-test", NOW);
    }

    private static final class InMemoryRoutes implements DeviceRouteDirectory {
        private final Map<DeviceId, DeviceRoute> values = new ConcurrentHashMap<>();
        @Override public void publish(DeviceRoute route) { claim(route); }
        @Override public DeviceRoute claim(DeviceRoute route) { return values.put(route.deviceId(), route); }
        @Override public Optional<DeviceRoute> find(DeviceId deviceId) { return Optional.ofNullable(values.get(deviceId)); }
        @Override public boolean touch(DeviceId d, SessionId s, long e, Instant seen, Instant expiry) { return false; }
        @Override public boolean remove(DeviceId d, SessionId s, long e) { return remove(d, "", "", s, e); }
        @Override public boolean remove(DeviceId d, String node, String instance, SessionId session, long epoch) {
            DeviceRoute current = values.get(d);
            return current != null && current.ownedBy(node, instance, session, epoch) && values.remove(d, current);
        }
    }

    private static final class InMemoryNodes implements GatewayNodeRegistry {
        private final Map<String, GatewayNode> values = new ConcurrentHashMap<>();
        void put(GatewayNode node) { values.put(node.gatewayNodeId(), node); }
        @Override public void register(GatewayNode node) { put(node); }
        @Override public Optional<GatewayNode> find(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public boolean refresh(String id, String instance, Instant seen, Instant expiry) { return false; }
        @Override public boolean removeIfOwned(String id, String instance) { return false; }
    }
}
