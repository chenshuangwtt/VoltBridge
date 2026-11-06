package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 创建完整全天覆盖的固定或分时资费草稿。 */
@Service
public class CreateTariffPlanUseCase {
    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public CreateTariffPlanUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
        this.tariffPlans = tariffPlans;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public TariffPlan execute(UUID tenantId, String name, String currency, PricingType pricingType,
                              String timezone, List<TariffPeriod> periods,
                              Instant effectiveFrom, Instant effectiveTo) {
        TariffPlan plan = TariffPlan.create(tenantId, name, currency, pricingType, timezone,
                periods, effectiveFrom, effectiveTo, timeProvider.now());
        tariffPlans.save(plan);
        return plan;
    }
}
