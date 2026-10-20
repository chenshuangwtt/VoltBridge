package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

public record HeartbeatEvent(UUID eventId, DeviceId deviceId, String sessionId, Instant deviceTime,
                             Instant occurredAt) implements DeviceEvent {
}
