package com.voltbridge.infra.rpc.grpc.client;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.DispatchCommandReply;
import com.voltbridge.contract.v1.DispatchCommandRequest;
import com.voltbridge.contract.v1.DispatchStatus;
import com.voltbridge.contract.v1.GatewayRpcServiceGrpc;
import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.port.DeviceCommandGateway;
import io.grpc.StatusRuntimeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.DeviceRouteDirectory;
import com.voltbridge.gateway.routing.GatewayNode;
import com.voltbridge.gateway.routing.GatewayNodeRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Business Service 根据 Redis 共享路由，将规范设备命令发送到拥有当前会话的 Gateway。 */
@Component
@ConditionalOnProperty(name = "voltbridge.deployment.mode", havingValue = "business")
public class GrpcDeviceCommandGateway implements DeviceCommandGateway {

    private final DeviceRouteDirectory routes;
    private final GatewayNodeRegistry nodes;
    private final GatewayGrpcClientRegistry clients;
    private final TimeProvider timeProvider;
    private final Duration timeout;

    public GrpcDeviceCommandGateway(DeviceRouteDirectory routes, GatewayNodeRegistry nodes,
                                    GatewayGrpcClientRegistry clients, TimeProvider timeProvider,
                                    @org.springframework.beans.factory.annotation.Value("${voltbridge.grpc.command-timeout:3s}") Duration timeout) {
        this.routes = routes;
        this.nodes = nodes;
        this.clients = clients;
        this.timeProvider = timeProvider;
        this.timeout = timeout;
    }

    @Override
    public CompletionStage<Void> dispatch(DeviceCommand command) {
        try {
            RouteTarget initial = resolve(command);
            DispatchCommandReply reply = send(command, initial);
            if (accepted(reply)) {
                return CompletableFuture.completedFuture(null);
            }
            if (isStale(reply)) {
                RouteTarget refreshed = resolve(command);
                if (!initial.sameOwner(refreshed)) {
                    DispatchCommandReply retried = send(command, refreshed);
                    if (accepted(retried)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return failed(retried);
                }
                clients.evict(initial.node().gatewayNodeId());
                return CompletableFuture.failedFuture(new VoltBridgeException("STALE_ROUTE",
                        "Gateway no longer owns routed session for device " + command.deviceId().value()));
            }
            return failed(reply);
        } catch (StatusRuntimeException exception) {
            return CompletableFuture.failedFuture(new VoltBridgeException("GATEWAY_UNAVAILABLE",
                    "Gateway command RPC failed: " + exception.getStatus().getCode()));
        } catch (VoltBridgeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private RouteTarget resolve(DeviceCommand command) {
        DeviceRoute route = routes.find(command.deviceId()).orElseThrow(() -> new VoltBridgeException("DEVICE_OFFLINE",
                "No active device route: " + command.deviceId().value()));
        Instant now = timeProvider.now();
        if (!route.leaseExpiresAt().isAfter(now)) {
            throw new VoltBridgeException("STALE_ROUTE", "Device route lease has expired: " + command.deviceId().value());
        }
        GatewayNode node = nodes.find(route.gatewayNodeId()).orElseThrow(() -> new VoltBridgeException("STALE_ROUTE",
                "Gateway node registration is missing: " + route.gatewayNodeId()));
        if (!node.isActiveAt(now) || !node.ownedBy(route.gatewayNodeInstanceId())) {
            throw new VoltBridgeException("STALE_ROUTE", "Gateway node registration does not own current route: " + route.gatewayNodeId());
        }
        return new RouteTarget(route, node);
    }

    private DispatchCommandReply send(DeviceCommand command, RouteTarget target) {
        try {
            return clients.clientFor(target.node()).withDeadlineAfter(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .dispatchCommand(DispatchCommandRequest.newBuilder().setCommand(DeviceProtoMapper.toProto(command)).build());
        } catch (StatusRuntimeException exception) {
            throw new VoltBridgeException("GATEWAY_UNAVAILABLE", "Gateway command RPC failed for "
                    + target.node().gatewayNodeId() + ": " + exception.getStatus().getCode());
        }
    }

    private boolean accepted(DispatchCommandReply reply) {
        return reply.getStatus() == DispatchStatus.DISPATCH_ACCEPTED;
    }

    private boolean isStale(DispatchCommandReply reply) {
        return reply.getStatus() == DispatchStatus.DISPATCH_SESSION_NOT_FOUND
                || reply.getStatus() == DispatchStatus.DISPATCH_ROUTE_STALE;
    }

    private CompletionStage<Void> failed(DispatchCommandReply reply) {
        return CompletableFuture.failedFuture(new VoltBridgeException(mapFailure(reply), failureMessage(reply)));
    }

    private String mapFailure(DispatchCommandReply reply) {
        if (isStale(reply)) {
            return "STALE_ROUTE";
        }
        if (reply.hasFailure() && "TRANSPORT_SEND_FAILED".equals(reply.getFailure().getCode())) {
            return "TRANSPORT_SEND_FAILED";
        }
        return "GATEWAY_UNAVAILABLE";
    }

    private String failureMessage(DispatchCommandReply reply) {
        return reply.hasFailure() ? reply.getFailure().getReason() : "Gateway rejected device command";
    }

    private record RouteTarget(DeviceRoute route, GatewayNode node) {
        boolean sameOwner(RouteTarget other) {
            return route.gatewayNodeId().equals(other.route.gatewayNodeId())
                    && route.gatewayNodeInstanceId().equals(other.route.gatewayNodeInstanceId())
                    && route.matches(other.route.sessionId(), other.route.connectionEpoch());
        }
    }
}
