package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 为资费 family 创建独立草稿版本，不修改源 revision。 */
@Service
public class CreateTariffRevisionUseCase {
    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public CreateTariffRevisionUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
        this.tariffPlans = tariffPlans;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public TariffPlan execute(UUID sourcePlanId, String name, String currency, PricingType pricingType,
                              String timezone, List<TariffPeriod> periods,
                              Instant effectiveFrom, Instant effectiveTo) {
        TariffPlan firstRead = tariffPlans.findById(sourcePlanId)
                .orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + sourcePlanId));
        tariffPlans.lockFamilyForRevision(firstRead.familyId());
        TariffPlan source = tariffPlans.findById(sourcePlanId)
                .orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + sourcePlanId));
        if (source.role() == TariffRole.FALLBACK) {
            throw new VoltBridgeException("FALLBACK_REVISION_USE_CASE_REQUIRED",
                    "Fallback tariff revisions must use the dedicated fallback revision operation");
        }
        int revision = tariffPlans.nextRevision(source.familyId());
        TariffPlan next = source.nextRevision(revision, name, currency, pricingType, timezone,
                periods, effectiveFrom, effectiveTo, timeProvider.now());
        tariffPlans.save(next);
        return next;
    }
}
