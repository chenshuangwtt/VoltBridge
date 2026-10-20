package com.voltbridge.gateway.routing;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.gateway.session.SessionId;

import java.time.Instant;
import java.util.Optional;

/**
 * 可替换的设备路由目录，只存储可序列化路由元数据，不存储活动连接对象。
 */
public interface DeviceRouteDirectory {

    void publish(DeviceRoute route);

    /**
     * 原子声明设备路由并返回替换前的路由。
     *
     * <p>分布式实现必须保证读取旧值和写入新值不可被另一节点的声明操作穿插；本地实现也使用
     * 原子 Map 操作保持与 Redis 一致的语义。</p>
     */
    default DeviceRoute claim(DeviceRoute route) {
        DeviceRoute previous = find(route.deviceId()).orElse(null);
        publish(route);
        return previous;
    }

    Optional<DeviceRoute> find(DeviceId deviceId);

    boolean touch(DeviceId deviceId, SessionId sessionId, long connectionEpoch,
                  Instant lastSeenAt, Instant leaseExpiresAt);

    /** 分布式刷新必须同时校验 Gateway 进程实例，避免旧进程复活新实例的路由。 */
    default boolean touch(DeviceId deviceId, String gatewayNodeId, String nodeInstanceId,
                          SessionId sessionId, long connectionEpoch, Instant lastSeenAt, Instant leaseExpiresAt) {
        return touch(deviceId, sessionId, connectionEpoch, lastSeenAt, leaseExpiresAt);
    }

    boolean remove(DeviceId deviceId, SessionId sessionId, long connectionEpoch);

    /** 分布式删除必须同时校验 Gateway 进程实例和会话围栏。 */
    default boolean remove(DeviceId deviceId, String gatewayNodeId, String nodeInstanceId,
                           SessionId sessionId, long connectionEpoch) {
        return remove(deviceId, sessionId, connectionEpoch);
    }
}
