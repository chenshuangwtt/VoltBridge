package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;

import java.time.Instant;
import java.util.UUID;

public record DeviceDisconnectedEvent(UUID eventId, DeviceId deviceId, DeviceProtocol protocol,
                                      String sessionId, String reason,
                                      Instant occurredAt) implements DeviceEvent {
}
