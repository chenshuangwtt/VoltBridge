package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 显式退役资费方案；既有账单与历史版本保持不变。 */
@Service
public class RetireTariffPlanUseCase {
    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public RetireTariffPlanUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
        this.tariffPlans = tariffPlans;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public TariffPlan execute(UUID tariffPlanId) {
        TariffPlan firstRead = tariffPlans.findById(tariffPlanId)
                .orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + tariffPlanId));
        tariffPlans.lockTenantForTariffLifecycle(firstRead.tenantId());
        TariffPlan plan = tariffPlans.findById(tariffPlanId)
                .orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + tariffPlanId));
        var now = timeProvider.now();
        if (plan.role() == TariffRole.FALLBACK) {
            throw new VoltBridgeException("FALLBACK_TARIFF_CANNOT_RETIRE",
                    "Fallback tariff revisions cannot be retired through the scheduled tariff lifecycle");
        }
        if (plan.status() == TariffPlanStatus.RETIRED) return plan;
        plan.retire(now);
        tariffPlans.save(plan);
        return plan;
    }
}
