package com.voltbridge.gateway.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Gateway 进程身份：稳定节点名配合每次启动唯一的实例围栏标识。 */
@Component
public class GatewayNodeIdentity {
    private final String gatewayNodeId;
    private final String nodeInstanceId;

    public GatewayNodeIdentity(@Value("${voltbridge.gateway.node-id:monolith}") String gatewayNodeId,
                               @Value("${voltbridge.gateway.node-instance-id:}") String configuredInstanceId) {
        this.gatewayNodeId = gatewayNodeId;
        this.nodeInstanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString() : configuredInstanceId;
    }

    public String gatewayNodeId() {
        return gatewayNodeId;
    }

    public String nodeInstanceId() {
        return nodeInstanceId;
    }
}
