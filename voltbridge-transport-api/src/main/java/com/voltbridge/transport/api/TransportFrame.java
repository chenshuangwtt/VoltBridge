package com.voltbridge.transport.api;

import java.time.Instant;
/**
 * 传输层完成边界切分后交给上层的一帧不可变数据。
 */
public record TransportFrame(String sessionId, TransportPayload payload, Instant receivedAt) {

    public TransportFrame {
        if (payload == null) {
            throw new IllegalArgumentException("transport payload must not be null");
        }
    }
}
