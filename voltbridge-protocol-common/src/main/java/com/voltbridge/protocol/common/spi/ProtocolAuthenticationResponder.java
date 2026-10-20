package com.voltbridge.protocol.common.spi;

import com.voltbridge.transport.api.TransportPayload;

/**
 * 协议认证响应编码器，由网关在业务认证结果产生后调用。
 */
@FunctionalInterface
public interface ProtocolAuthenticationResponder {

    TransportPayload encode(boolean accepted);
}
