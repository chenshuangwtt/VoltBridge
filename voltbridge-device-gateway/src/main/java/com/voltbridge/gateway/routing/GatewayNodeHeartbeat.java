package com.voltbridge.gateway.routing;

import com.voltbridge.common.time.TimeProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis 模式下 Gateway 节点的租约心跳。
 *
 * <p>{@code gatewayNodeId} 是部署配置的稳定逻辑名；{@code nodeInstanceId} 是每个进程启动生成的
 * 围栏标识，因此旧进程在重启后不能刷新或删除新进程注册的信息。</p>
 */
@Component
@ConditionalOnProperty(name = "voltbridge.routing.mode", havingValue = "redis")
public class GatewayNodeHeartbeat {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayNodeHeartbeat.class);

    private final GatewayNodeRegistry nodes;
    private final TimeProvider timeProvider;
    private final GatewayNodeIdentity nodeIdentity;
    private final String advertisedHost;
    private final int advertisedPort;
    private final Duration ttl;
    private volatile Instant startedAt;

    public GatewayNodeHeartbeat(GatewayNodeRegistry nodes, TimeProvider timeProvider,
                                GatewayNodeIdentity nodeIdentity,
                                @Value("${voltbridge.gateway.grpc.advertised-host:localhost}") String advertisedHost,
                                @Value("${voltbridge.gateway.grpc.advertised-port:${voltbridge.grpc.server-port:9090}}") int advertisedPort,
                                @Value("${voltbridge.gateway.node-ttl:PT90S}") Duration ttl) {
        this.nodes = nodes;
        this.timeProvider = timeProvider;
        this.nodeIdentity = nodeIdentity;
        this.advertisedHost = advertisedHost;
        this.advertisedPort = advertisedPort;
        this.ttl = ttl;
    }

    @PostConstruct
    void register() {
        Instant now = timeProvider.now();
        startedAt = now;
        nodes.register(new GatewayNode(nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId(), advertisedHost, advertisedPort,
                now, now, now.plus(ttl)));
        LOGGER.info("Gateway node registered: gatewayNodeId={}, nodeInstanceId={}, grpcEndpoint={}:{}",
                nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId(), advertisedHost, advertisedPort);
    }

    @Scheduled(fixedDelayString = "${voltbridge.gateway.node-refresh-interval:PT30S}")
    void refresh() {
        Instant now = timeProvider.now();
        try {
            if (!nodes.refresh(nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId(), now, now.plus(ttl))) {
                LOGGER.warn("Gateway node heartbeat rejected as stale: gatewayNodeId={}, nodeInstanceId={}",
                        nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Gateway node heartbeat failed: gatewayNodeId={}, nodeInstanceId={}, reason={}",
                    nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId(), exception.getMessage());
        }
    }

    @PreDestroy
    void remove() {
        try {
            nodes.removeIfOwned(nodeIdentity.gatewayNodeId(), nodeIdentity.nodeInstanceId());
        } catch (RuntimeException exception) {
            LOGGER.warn("Gateway node deregistration failed; TTL will expire metadata: gatewayNodeId={}, reason={}",
                    nodeIdentity.gatewayNodeId(), exception.getMessage());
        }
    }

    public String nodeInstanceId() {
        return nodeIdentity.nodeInstanceId();
    }
}
