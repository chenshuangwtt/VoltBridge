package com.voltbridge.gateway.session;

import java.util.Collection;
import java.util.Optional;

/**
 * 按 sessionId 管理当前网关进程内 {@link DeviceSession} 的注册表。
 *
 * <p>注册表拥有活动连接引用，因此必须保持本地内存语义；设备编码到会话的查询由
 * {@code DeviceRouteDirectory} 负责，两者不能合并。</p>
 */
public interface DeviceSessionRegistry {

    void register(DeviceSession session);

    Optional<DeviceSession> find(SessionId sessionId);

    Optional<DeviceSession> remove(SessionId sessionId);

    /** 当前进程内会话快照，仅供网关租约刷新等本地运行时维护使用。 */
    default Collection<DeviceSession> sessions() {
        return java.util.List.of();
    }

    int size();
}
