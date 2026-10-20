package com.voltbridge.transport.api;

/** 完成 WebSocket 聚合后的不可变 UTF-8 文本载荷。 */
public record TextTransportPayload(String value) implements TransportPayload {

    public TextTransportPayload {
        if (value == null) {
            throw new IllegalArgumentException("text transport payload must not be null");
        }
    }
}
