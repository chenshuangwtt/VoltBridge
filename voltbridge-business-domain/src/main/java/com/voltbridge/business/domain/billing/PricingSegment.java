package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.util.Objects;

/** 实际分配到某个资费时段的电量、单价及金额。 */
public record PricingSegment(int sequenceNo, TariffPeriodType periodType, int startMinute, int endMinute,
                             BigDecimal energyKwh, BigDecimal energyPricePerKwh,
                             BigDecimal serviceFeePerKwh, BigDecimal energyFee,
                             BigDecimal serviceFee, BigDecimal reconciliationAdjustmentKwh) {
    public PricingSegment {
        if (sequenceNo < 1) throw new IllegalArgumentException("sequenceNo must be positive");
        periodType = Objects.requireNonNull(periodType);
        energyKwh = BillingDecimalPolicy.energy(energyKwh);
        energyPricePerKwh = BillingDecimalPolicy.unitPrice(energyPricePerKwh, "energyPricePerKwh");
        serviceFeePerKwh = BillingDecimalPolicy.unitPrice(serviceFeePerKwh, "serviceFeePerKwh");
        energyFee = BillingDecimalPolicy.money(energyFee);
        serviceFee = BillingDecimalPolicy.money(serviceFee);
        reconciliationAdjustmentKwh = Objects.requireNonNull(reconciliationAdjustmentKwh);
    }
}
