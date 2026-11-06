package com.voltbridge.business.domain.billing;

import com.voltbridge.device.model.DeviceProtocol;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 已关联真实充电交易、可作为账单电量证据的累计电表读数。 */
public record ChargingMeterSample(UUID eventId, UUID transactionId, Instant occurredAt,
                                  BigDecimal energyKwh, BigDecimal powerKw, Integer stateOfCharge,
                                  DeviceProtocol protocol) {
    public ChargingMeterSample {
        eventId = Objects.requireNonNull(eventId, "eventId");
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        energyKwh = BillingDecimalPolicy.energy(energyKwh);
        if (powerKw != null && powerKw.signum() < 0) {
            throw new IllegalArgumentException("powerKw must be non-negative");
        }
        protocol = Objects.requireNonNull(protocol, "protocol");
    }
}
