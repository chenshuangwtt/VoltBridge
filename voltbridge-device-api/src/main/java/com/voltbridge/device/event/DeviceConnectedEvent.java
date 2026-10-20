package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;

import java.time.Instant;
import java.util.UUID;

public record DeviceConnectedEvent(UUID eventId, DeviceId deviceId, DeviceProtocol protocol,
                                   String protocolVersion, String sessionId, String remoteAddress,
                                   Instant occurredAt) implements DeviceEvent {
}
