package com.voltbridge.gateway.routing;

import java.time.Instant;
import java.util.Objects;

/**
 * 可由 Business 访问的 Gateway 节点注册信息。
 *
 * <p>该对象仅描述短生命周期的网络寻址元数据；它不包含设备业务状态、Netty Channel 或持久化实体。</p>
 */
public record GatewayNode(String gatewayNodeId, String nodeInstanceId, String grpcHost, int grpcPort,
                          Instant startedAt, Instant lastHeartbeatAt, Instant expiresAt) {
    public GatewayNode {
        requireText(gatewayNodeId, "gatewayNodeId");
        requireText(nodeInstanceId, "nodeInstanceId");
        requireText(grpcHost, "grpcHost");
        if (grpcPort < 1 || grpcPort > 65535) {
            throw new IllegalArgumentException("grpcPort must be between 1 and 65535");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public boolean isActiveAt(Instant now) {
        return expiresAt.isAfter(now);
    }

    public boolean ownedBy(String expectedNodeInstanceId) {
        return nodeInstanceId.equals(expectedNodeInstanceId);
    }
}
