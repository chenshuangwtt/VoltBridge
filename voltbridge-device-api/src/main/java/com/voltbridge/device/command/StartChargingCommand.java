package com.voltbridge.device.command;

import com.voltbridge.device.model.DeviceId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StartChargingCommand(
        UUID commandId,
        DeviceId deviceId,
        String connectorNo,
        BigDecimal limitAmount,
        String logicalCardNo,
        String physicalCardNo,
        String businessRef,
        Instant createdAt) implements DeviceCommand {
}
