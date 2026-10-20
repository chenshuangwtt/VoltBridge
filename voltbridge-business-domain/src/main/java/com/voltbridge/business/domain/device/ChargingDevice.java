package com.voltbridge.business.domain.device;

import com.voltbridge.device.model.DeviceProtocol;

import java.time.Instant;
import java.util.UUID;

public record ChargingDevice(
        UUID id,
        UUID tenantId,
        UUID stationId,
        String deviceCode,
        String deviceName,
        DeviceProtocol protocol,
        String protocolVersion,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
