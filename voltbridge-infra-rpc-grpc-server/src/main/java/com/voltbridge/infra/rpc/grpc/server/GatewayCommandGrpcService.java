package com.voltbridge.infra.rpc.grpc.server;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.*;
import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.port.DeviceCommandGateway;
import io.grpc.stub.StreamObserver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Gateway Service 的命令入口，只做规范命令的技术接收和本地设备会话发送。 */
@Component
@ConditionalOnProperty(name = "voltbridge.deployment.mode", havingValue = "gateway")
public class GatewayCommandGrpcService extends GatewayRpcServiceGrpc.GatewayRpcServiceImplBase {

    private final DeviceCommandGateway localCommandGateway;
    private final Duration deliveryDedupRetention;
    private final ConcurrentMap<UUID, CompletableFuture<DispatchCommandReply>> deliveries = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayCommandGrpcService(DeviceCommandGateway localCommandGateway,
                                     @org.springframework.beans.factory.annotation.Value("${voltbridge.gateway.command-delivery-dedup-retention:PT2M}") Duration deliveryDedupRetention) {
        this.localCommandGateway = localCommandGateway;
        this.deliveryDedupRetention = deliveryDedupRetention;
    }

    /** 保留现有 gRPC 单元测试的直接构造方式。 */
    public GatewayCommandGrpcService(DeviceCommandGateway localCommandGateway) {
        this(localCommandGateway, Duration.ofMinutes(2));
    }

    @Override
    public void dispatchCommand(DispatchCommandRequest request, StreamObserver<DispatchCommandReply> observer) {
        try {
            DeviceCommand command = DeviceProtoMapper.fromProto(request.getCommand());
            dispatchOnce(command).whenComplete((reply, error) -> {
                observer.onNext(error == null ? reply : rejected(command.commandId().toString(), error));
                observer.onCompleted();
            });
        } catch (RuntimeException exception) {
            observer.onNext(rejected("", exception));
            observer.onCompleted();
        }
    }

    /** 同一 Gateway 对相同 commandId 共享一次本地下发结果，避免 RPC 重放产生两条协议待响应。 */
    private CompletableFuture<DispatchCommandReply> dispatchOnce(DeviceCommand command) {
        CompletableFuture<DispatchCommandReply> candidate = new CompletableFuture<>();
        CompletableFuture<DispatchCommandReply> existing = deliveries.putIfAbsent(command.commandId(), candidate);
        if (existing != null) {
            return existing;
        }
        try {
            localCommandGateway.dispatch(command).whenComplete((ignored, error) -> {
                if (error == null) {
                    candidate.complete(DispatchCommandReply.newBuilder().setCommandId(command.commandId().toString())
                            .setStatus(DispatchStatus.DISPATCH_ACCEPTED).build());
                } else {
                    candidate.complete(rejected(command.commandId().toString(), error));
                }
            });
        } catch (RuntimeException exception) {
            candidate.complete(rejected(command.commandId().toString(), exception));
        }
        candidate.whenComplete((ignored, error) -> CompletableFuture.delayedExecutor(
                        deliveryDedupRetention.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> deliveries.remove(command.commandId(), candidate)));
        return candidate;
    }

    private DispatchCommandReply rejected(String commandId, Throwable error) {
        String code = error instanceof VoltBridgeException exception ? exception.code() : "GATEWAY_REJECTED";
        DispatchStatus status = switch (code) {
            case "DEVICE_OFFLINE" -> DispatchStatus.DISPATCH_SESSION_NOT_FOUND;
            case "STALE_ROUTE" -> DispatchStatus.DISPATCH_ROUTE_STALE;
            case "TRANSPORT_SEND_FAILED" -> DispatchStatus.DISPATCH_REJECTED;
            default -> DispatchStatus.DISPATCH_REJECTED;
        };
        return DispatchCommandReply.newBuilder().setCommandId(commandId).setStatus(status)
                .setFailure(FailureDetail.newBuilder().setCode(code).setReason(error.getMessage() == null ? code : error.getMessage())).build();
    }
}
