package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Meter timeline 上的真实累计读数。 */
public record MeterPoint(Instant occurredAt, BigDecimal energyKwh) {
    public MeterPoint {
        occurredAt = Objects.requireNonNull(occurredAt);
        energyKwh = BillingDecimalPolicy.energy(energyKwh);
    }
}
