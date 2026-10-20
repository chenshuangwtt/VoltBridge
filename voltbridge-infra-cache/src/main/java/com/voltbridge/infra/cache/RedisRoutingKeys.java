package com.voltbridge.infra.cache;

/** Redis 路由元数据键的唯一构造入口，避免各实现散落硬编码命名空间。 */
final class RedisRoutingKeys {
    private static final String PREFIX = "voltbridge:v1:";

    private RedisRoutingKeys() {
    }

    static String deviceRoute(String deviceCode) {
        return PREFIX + "route:device:{" + deviceCode + "}";
    }

    static String gatewayNode(String gatewayNodeId) {
        return PREFIX + "gateway:node:{" + gatewayNodeId + "}";
    }
}
