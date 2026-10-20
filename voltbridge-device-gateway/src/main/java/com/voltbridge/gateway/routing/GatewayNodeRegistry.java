package com.voltbridge.gateway.routing;

import java.time.Instant;
import java.util.Optional;

/**
 * Gateway 节点的短租约注册表。
 *
 * <p>Redis 等基础设施实现负责 TTL 与原子所有权校验；业务层只读取结果，不直接依赖 Redis 客户端。</p>
 */
public interface GatewayNodeRegistry {
    void register(GatewayNode node);

    Optional<GatewayNode> find(String gatewayNodeId);

    boolean refresh(String gatewayNodeId, String nodeInstanceId, Instant lastHeartbeatAt, Instant expiresAt);

    boolean removeIfOwned(String gatewayNodeId, String nodeInstanceId);
}
