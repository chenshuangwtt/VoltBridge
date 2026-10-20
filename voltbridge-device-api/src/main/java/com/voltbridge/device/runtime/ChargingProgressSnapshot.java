package com.voltbridge.device.runtime;

import com.voltbridge.device.model.DeviceId;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record ChargingProgressSnapshot(DeviceId deviceId, int connectorNo, String transactionId,
                                       BigDecimal outputVoltage, BigDecimal outputCurrent,
                                       BigDecimal outputPowerKw, Integer stateOfCharge, BigDecimal energyKwh,
                                       Duration chargingDuration, BigDecimal chargingAmount,
                                       String additionalInfo, Instant reportedAt) {
}
