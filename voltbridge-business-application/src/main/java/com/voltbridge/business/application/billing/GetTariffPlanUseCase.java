package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** 查询单一资费方案。 */
@Service public class GetTariffPlanUseCase {
    private final TariffPlanRepository tariffPlans;
    public GetTariffPlanUseCase(TariffPlanRepository tariffPlans) { this.tariffPlans = tariffPlans; }
    public TariffPlan execute(UUID id) { return tariffPlans.findById(id).orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + id)); }
}
