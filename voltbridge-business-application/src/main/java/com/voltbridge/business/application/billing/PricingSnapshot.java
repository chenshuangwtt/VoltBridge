package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.PricingSegment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 写入 billing_record 的不可变计价证据。 */
public record PricingSnapshot(UUID tariffPlanId, UUID familyId, int revision, String name,
                              String role, String currency, String pricingType, String timezone,
                              Instant effectiveFrom, Instant effectiveTo,
                              List<TariffPeriodSnapshot> periods,
                              List<PricingSegmentSnapshot> appliedSegments,
                              BigDecimal energyKwh, BigDecimal energyFee,
                              BigDecimal serviceFee, BigDecimal subtotalAmount,
                              BigDecimal totalAmount, BigDecimal meterReconciliationDeltaKwh) {
    public PricingSnapshot {
        periods = List.copyOf(periods);
        appliedSegments = List.copyOf(appliedSegments);
    }

    public static PricingSnapshot from(TariffPlan tariff, PricingResult result) {
        return new PricingSnapshot(tariff.id(), tariff.familyId(), tariff.revision(), tariff.name(),
                tariff.role().name(), tariff.currency(), tariff.pricingType().name(), tariff.timezone(),
                tariff.effectiveFrom(), tariff.effectiveTo(),
                tariff.periods().stream().map(TariffPeriodSnapshot::from).toList(),
                result.segments().stream().map(PricingSegmentSnapshot::from).toList(),
                result.energyKwh(), result.energyFee(), result.serviceFee(),
                result.subtotalAmount(), result.totalAmount(), result.meterReconciliationDeltaKwh());
    }

    public record TariffPeriodSnapshot(int sequenceNo, String periodType, int startMinute, int endMinute,
                                       BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh) {
        private static TariffPeriodSnapshot from(TariffPeriod period) {
            return new TariffPeriodSnapshot(period.sequenceNo(), period.periodType().name(),
                    period.startMinute(), period.endMinute(), period.energyPricePerKwh(),
                    period.serviceFeePerKwh());
        }
    }

    public record PricingSegmentSnapshot(int sequenceNo, String periodType, int startMinute, int endMinute,
                                         BigDecimal energyKwh, BigDecimal energyPricePerKwh,
                                         BigDecimal serviceFeePerKwh, BigDecimal energyFee,
                                         BigDecimal serviceFee, BigDecimal reconciliationAdjustmentKwh) {
        private static PricingSegmentSnapshot from(PricingSegment segment) {
            return new PricingSegmentSnapshot(segment.sequenceNo(), segment.periodType().name(),
                    segment.startMinute(), segment.endMinute(), segment.energyKwh(),
                    segment.energyPricePerKwh(), segment.serviceFeePerKwh(), segment.energyFee(),
                    segment.serviceFee(), segment.reconciliationAdjustmentKwh());
        }
    }
}
