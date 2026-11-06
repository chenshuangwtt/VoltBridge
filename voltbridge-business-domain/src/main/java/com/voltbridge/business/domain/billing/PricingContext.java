package com.voltbridge.business.domain.billing;

import com.voltbridge.business.domain.transaction.ChargingTransaction;

import java.util.Objects;

/** 单笔交易的完整计价输入；FIXED 方案不要求 meter timeline。 */
public record PricingContext(ChargingTransaction transaction, TariffPlan tariffPlan,
                             MeterTimeline meterTimeline) {
    public PricingContext {
        transaction = Objects.requireNonNull(transaction);
        tariffPlan = Objects.requireNonNull(tariffPlan);
        meterTimeline = meterTimeline == null ? MeterTimeline.notRequired() : meterTimeline;
    }
}
