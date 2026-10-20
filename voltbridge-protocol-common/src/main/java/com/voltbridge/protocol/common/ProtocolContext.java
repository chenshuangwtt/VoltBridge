package com.voltbridge.protocol.common;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 协议插件可见的强类型会话上下文，不暴露 Netty 或网关内部对象。
 */
public record ProtocolContext(String sessionId, String remoteAddress, DeviceProtocol protocol,
                              Optional<DeviceId> deviceId, IntSupplier sequenceSupplier,
                              Map<String, String> transportAttributes) {

    public ProtocolContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        if (remoteAddress == null || remoteAddress.isBlank()) {
            throw new IllegalArgumentException("remote address must not be blank");
        }
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(deviceId, "device id optional must not be null");
        Objects.requireNonNull(sequenceSupplier, "sequence supplier must not be null");
        transportAttributes = Map.copyOf(transportAttributes);
    }

    public ProtocolContext(String sessionId, String remoteAddress, DeviceProtocol protocol,
                           Optional<DeviceId> deviceId, IntSupplier sequenceSupplier) {
        this(sessionId, remoteAddress, protocol, deviceId, sequenceSupplier, Map.of());
    }

    public int nextSequence() {
        return sequenceSupplier.getAsInt();
    }
}
