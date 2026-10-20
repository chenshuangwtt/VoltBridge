package com.voltbridge.gateway.routing;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.gateway.session.SessionId;

import java.time.Instant;
import java.util.Objects;

/**
 * 设备网关拥有的可序列化路由值：{@code deviceCode -> gatewayNodeId -> sessionId}。
 *
 * <p>{@code connectionEpoch} 是会话围栏代号，区分同一设备的多次连接，避免旧连接断开时删除新路由。
 * 本对象不持有 {@code TransportSession} 或 Netty Channel，也不是 PostgreSQL 业务实体。</p>
 */
public record DeviceRoute(DeviceId deviceId, String gatewayNodeId, String gatewayNodeInstanceId, SessionId sessionId,
                          long connectionEpoch, DeviceProtocol protocol, Instant authenticatedAt,
                          Instant lastSeenAt, Instant leaseExpiresAt) {

    public DeviceRoute {
        Objects.requireNonNull(deviceId, "device id must not be null");
        if (gatewayNodeId == null || gatewayNodeId.isBlank()) {
            throw new IllegalArgumentException("gateway node id must not be blank");
        }
        if (gatewayNodeInstanceId == null || gatewayNodeInstanceId.isBlank()) {
            throw new IllegalArgumentException("gateway node instance id must not be blank");
        }
        Objects.requireNonNull(sessionId, "session id must not be null");
        if (connectionEpoch <= 0) {
            throw new IllegalArgumentException("connection epoch must be positive");
        }
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticated time must not be null");
        Objects.requireNonNull(lastSeenAt, "last seen time must not be null");
        Objects.requireNonNull(leaseExpiresAt, "lease expiration must not be null");
    }

    /** 兼容单体与既有测试夹具；本地路由使用固定的单进程实例标识。 */
    public DeviceRoute(DeviceId deviceId, String gatewayNodeId, SessionId sessionId,
                       long connectionEpoch, DeviceProtocol protocol, Instant authenticatedAt,
                       Instant lastSeenAt, Instant leaseExpiresAt) {
        this(deviceId, gatewayNodeId, "local", sessionId, connectionEpoch, protocol,
                authenticatedAt, lastSeenAt, leaseExpiresAt);
    }

    public boolean matches(SessionId expectedSessionId, long expectedConnectionEpoch) {
        return sessionId.equals(expectedSessionId) && connectionEpoch == expectedConnectionEpoch;
    }

    /** 用节点进程实例、会话 ID 与围栏代号共同确认跨节点路由所有权。 */
    public boolean ownedBy(String expectedGatewayNodeId, String expectedNodeInstanceId,
                           SessionId expectedSessionId, long expectedConnectionEpoch) {
        return gatewayNodeId.equals(expectedGatewayNodeId)
                && gatewayNodeInstanceId.equals(expectedNodeInstanceId)
                && matches(expectedSessionId, expectedConnectionEpoch);
    }
}
