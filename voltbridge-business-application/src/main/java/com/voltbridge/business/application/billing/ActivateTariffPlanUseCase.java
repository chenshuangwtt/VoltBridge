package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 显式启用资费草稿，不提供在原方案上修改费率的入口。 */
@Service
public class ActivateTariffPlanUseCase {
    private final TariffPlanRepository tariffPlans; private final TimeProvider timeProvider;
    public ActivateTariffPlanUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) { this.tariffPlans = tariffPlans; this.timeProvider = timeProvider; }
    public TariffPlan execute(UUID tariffPlanId) {
        TariffPlan plan = tariffPlans.findById(tariffPlanId).orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + tariffPlanId));
        plan.activate(timeProvider.now()); tariffPlans.save(plan); return plan;
    }
}
