package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Atomically closes the current fallback revision and inserts its next all-day FIXED revision. */
@Service
public class CreateFallbackTariffRevisionUseCase {
    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public CreateFallbackTariffRevisionUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
        this.tariffPlans = tariffPlans;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public TariffPlan execute(UUID tenantId, Instant switchAt,
                              BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh) {
        if (switchAt == null || switchAt.isBefore(timeProvider.now())) {
            throw new VoltBridgeException("FALLBACK_SWITCH_TIME_INVALID",
                    "Fallback switch time must not precede the current platform time");
        }

        tariffPlans.lockTenantForTariffLifecycle(tenantId);
        TariffPlan current = FallbackTariffChain.currentRevision(tenantId,
                tariffPlans.findByTenantId(tenantId));
        tariffPlans.lockFamilyForRevision(current.familyId());
        current = FallbackTariffChain.currentRevision(tenantId, tariffPlans.findByTenantId(tenantId));

        current.closeFallbackAt(switchAt);
        int revision = tariffPlans.nextRevision(current.familyId());
        TariffPlan next = current.nextRevision(revision, EnsureTenantFallbackTariffUseCase.DEFAULT_NAME,
                current.currency(), PricingType.FIXED,
                current.timezone(), List.of(new TariffPeriod(1, TariffPeriodType.FLAT, 0, 1440,
                        energyPricePerKwh, serviceFeePerKwh)), switchAt, null, timeProvider.now());
        next.activate(timeProvider.now());

        tariffPlans.save(current);
        tariffPlans.save(next);
        return next;
    }
}
