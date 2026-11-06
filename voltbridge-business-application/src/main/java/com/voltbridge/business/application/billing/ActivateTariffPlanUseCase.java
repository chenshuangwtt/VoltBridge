package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 按 tenant 串行检查有效区间后启用草稿资费。 */
@Service
public class ActivateTariffPlanUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActivateTariffPlanUseCase.class);
    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public ActivateTariffPlanUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
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
        if (plan.status() == TariffPlanStatus.ACTIVE) return plan;
        if (plan.status() == TariffPlanStatus.RETIRED) {
            throw new VoltBridgeException("TARIFF_RETIRED", "Retired tariff plan cannot be activated");
        }

        List<TariffPlan> conflicts = plan.role() == TariffRole.SCHEDULED
                ? tariffPlans.findActiveOverlapping(plan.tenantId(),
                        plan.effectiveFrom(), plan.effectiveTo(), plan.id())
                : List.of();
        if (!conflicts.isEmpty()) {
            TariffPlan conflict = conflicts.getFirst();
            String candidateRange = plan.effectiveRange().toIntervalString();
            String existingRange = conflict.effectiveRange().toIntervalString();
            LOGGER.warn("Tariff activation range conflict: tenantId={}, candidateTariffId={}, conflictingTariffId={}, candidateRange={}, existingRange={}",
                    plan.tenantId(), plan.id(), conflict.id(), candidateRange, existingRange);
            throw new VoltBridgeException("TARIFF_EFFECTIVE_RANGE_CONFLICT",
                    "Tariff effective range conflicts with active tariff '" + conflict.name()
                            + "' (tariffPlanId=" + conflict.id() + ", revision=" + conflict.revision()
                            + ", existingRange=" + existingRange + ", candidateTariffPlanId=" + plan.id()
                            + ", candidateRange=" + candidateRange + ")");
        }
        plan.activate(timeProvider.now());
        tariffPlans.save(plan);
        return plan;
    }
}
