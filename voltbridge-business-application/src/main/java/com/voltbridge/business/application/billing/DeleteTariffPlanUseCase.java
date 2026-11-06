package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Only unreferenced draft revisions can be permanently removed. */
@Service
public class DeleteTariffPlanUseCase {
    private final TariffPlanRepository tariffPlans;
    private final BillingRecordRepository billingRecords;

    public DeleteTariffPlanUseCase(TariffPlanRepository tariffPlans,
                                   BillingRecordRepository billingRecords) {
        this.tariffPlans = tariffPlans;
        this.billingRecords = billingRecords;
    }

    @Transactional
    public void execute(UUID tariffPlanId) {
        TariffPlan firstRead = requirePlan(tariffPlanId);
        tariffPlans.lockTenantForTariffLifecycle(firstRead.tenantId());
        tariffPlans.lockFamilyForRevision(firstRead.familyId());

        TariffPlan plan = requirePlan(tariffPlanId);
        if (plan.role() == TariffRole.FALLBACK) {
            throw new VoltBridgeException("FALLBACK_TARIFF_CANNOT_DELETE",
                    "Fallback tariff revisions are retained as the tenant billing safety net");
        }
        if (plan.status() == TariffPlanStatus.ACTIVE) {
            throw new VoltBridgeException("TARIFF_ACTIVE_CANNOT_DELETE",
                    "Active tariff plans cannot be deleted; retire the tariff first when allowed");
        }
        if (plan.status() == TariffPlanStatus.RETIRED) {
            throw new VoltBridgeException("TARIFF_HISTORICAL_CANNOT_DELETE",
                    "Retired tariff plans are retained for historical billing and audit references");
        }
        if (billingRecords.existsByTariffPlanId(plan.id())) {
            throw referenced(plan.id());
        }

        try {
            if (!tariffPlans.delete(plan.id())) {
                throw new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + plan.id());
            }
        } catch (DataIntegrityViolationException foreignKeyRace) {
            throw referenced(plan.id());
        }
    }

    private TariffPlan requirePlan(UUID id) {
        return tariffPlans.findById(id)
                .orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "Tariff plan not found: " + id));
    }

    private VoltBridgeException referenced(UUID id) {
        return new VoltBridgeException("TARIFF_REFERENCED_CANNOT_DELETE",
                "Tariff plan cannot be deleted because a billing record references it: " + id);
    }
}
