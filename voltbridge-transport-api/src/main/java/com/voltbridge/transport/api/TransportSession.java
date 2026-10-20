package com.voltbridge.transport.api;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 传输层拥有的一条真实活动连接，是 TCP Channel 或 WebSocket Channel 的上层抽象。
 *
 * <p>它只负责载荷收发、地址、握手属性和连接生命周期，可以被本地 {@code DeviceSession}
 * 持有；不得进入 PostgreSQL、路由值对象或业务模型，也不解析登录、心跳和充电语义。</p>
 */
public interface TransportSession {

    String id();

    TransportEndpointId endpointId();

    SocketAddress remoteAddress();

    SocketAddress localAddress();

    boolean isOpen();

    CompletionStage<Void> send(TransportPayload payload);

    /** 传输握手产生的非敏感会话属性，例如 WebSocket URL 中的设备编码。 */
    default Map<String, String> attributes() {
        return Map.of();
    }

    CompletionStage<Void> close();
}
