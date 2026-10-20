package com.voltbridge.infra.rpc.grpc.client;

import com.voltbridge.contract.v1.GatewayRpcServiceGrpc;
import com.voltbridge.gateway.routing.GatewayNode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按 Gateway 节点复用的 gRPC 通道注册表。
 *
 * <p>节点重启、实例围栏变化或广告地址变化时会关闭旧通道后重建，避免每条命令新建一个 Channel。</p>
 */
@Component
public class GatewayGrpcClientRegistry {
    private final ConcurrentMap<String, ClientHolder> clients = new ConcurrentHashMap<>();

    public GatewayRpcServiceGrpc.GatewayRpcServiceBlockingStub clientFor(GatewayNode node) {
        ClientHolder holder = clients.compute(node.gatewayNodeId(), (ignored, current) -> {
            if (current != null && current.matches(node)) {
                return current;
            }
            if (current != null) {
                current.channel.shutdownNow();
            }
            ManagedChannel channel = ManagedChannelBuilder.forAddress(node.grpcHost(), node.grpcPort())
                    .usePlaintext().build();
            return new ClientHolder(node, channel, GatewayRpcServiceGrpc.newBlockingStub(channel));
        });
        return holder.stub;
    }

    public void evict(String gatewayNodeId) {
        ClientHolder removed = clients.remove(gatewayNodeId);
        if (removed != null) {
            removed.channel.shutdownNow();
        }
    }

    @PreDestroy
    void close() {
        clients.values().forEach(holder -> holder.channel.shutdownNow());
        clients.clear();
    }

    private record ClientHolder(GatewayNode node, ManagedChannel channel,
                                GatewayRpcServiceGrpc.GatewayRpcServiceBlockingStub stub) {
        boolean matches(GatewayNode candidate) {
            return node.nodeInstanceId().equals(candidate.nodeInstanceId())
                    && node.grpcHost().equals(candidate.grpcHost())
                    && node.grpcPort() == candidate.grpcPort();
        }
    }
}
