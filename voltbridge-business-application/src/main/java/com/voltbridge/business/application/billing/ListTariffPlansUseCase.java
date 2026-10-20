package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** 按租户列出资费方案。 */
@Service public class ListTariffPlansUseCase {
    private final TariffPlanRepository tariffPlans;
    public ListTariffPlansUseCase(TariffPlanRepository tariffPlans) { this.tariffPlans = tariffPlans; }
    public List<TariffPlan> execute(UUID tenantId) { return tariffPlans.findByTenantId(tenantId); }
}
