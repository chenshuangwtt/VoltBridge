package com.voltbridge.device.event;

import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

public record ConnectorStatusEvent(UUID eventId, DeviceId deviceId, int connectorNo,
                                   ConnectorStatus status, Instant occurredAt) implements DeviceEvent {
}
