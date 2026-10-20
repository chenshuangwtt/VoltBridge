package com.voltbridge.transport.api;

/**
 * 传输层与协议层之间的不可变载荷，明确区分二进制 TCP 帧和 WebSocket 文本消息。
 *
 * <p>该抽象不暴露 Netty 对象，也不解释任何充电协议语义。</p>
 */
public sealed interface TransportPayload permits BinaryTransportPayload, TextTransportPayload {
}
