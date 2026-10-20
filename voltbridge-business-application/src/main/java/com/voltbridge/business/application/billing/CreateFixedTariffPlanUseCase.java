package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 创建固定费率草稿；改价通过创建新方案实现，不修改历史费率。 */
@Service
public class CreateFixedTariffPlanUseCase {
    private final TariffPlanRepository tariffPlans; private final TimeProvider timeProvider;
    public CreateFixedTariffPlanUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) { this.tariffPlans = tariffPlans; this.timeProvider = timeProvider; }
    public TariffPlan execute(UUID tenantId, String name, String currency, BigDecimal energyPricePerKwh,
                              BigDecimal serviceFeePerKwh, Instant effectiveFrom, Instant effectiveTo) {
        TariffPlan plan = TariffPlan.create(tenantId, name, currency, energyPricePerKwh, serviceFeePerKwh,
                effectiveFrom, effectiveTo, timeProvider.now());
        tariffPlans.save(plan); return plan;
    }
}
