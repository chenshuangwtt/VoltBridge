package com.voltbridge.gateway.session;

import com.voltbridge.transport.api.TransportConnectionListener;

/**
 * 传输连接进入设备网关的唯一编排边界。
 *
 * <p>本地实现负责注册会话、处理完整协议帧、执行业务认证和清理路由。</p>
 */
public interface GatewayConnectionCoordinator extends TransportConnectionListener {
}
