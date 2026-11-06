package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.common.exception.VoltBridgeException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validates the one immutable, contiguous fallback family maintained for each tenant. */
final class FallbackTariffChain {
    private FallbackTariffChain() { }

    static TariffPlan currentRevision(UUID tenantId, List<TariffPlan> tenantPlans) {
        List<TariffPlan> fallback = tenantPlans.stream()
                .filter(plan -> plan.role() == TariffRole.FALLBACK)
                .sorted(Comparator.comparingInt(TariffPlan::revision))
                .toList();
        if (fallback.isEmpty()) {
            throw new VoltBridgeException("FALLBACK_TARIFF_NOT_FOUND",
                    "Tenant has no fallback tariff family: " + tenantId);
        }

        UUID familyId = fallback.getFirst().familyId();
        for (int index = 0; index < fallback.size(); index++) {
            TariffPlan revision = fallback.get(index);
            if (!tenantId.equals(revision.tenantId()) || !familyId.equals(revision.familyId())
                    || revision.revision() != index + 1 || revision.pricingType() != PricingType.FIXED
                    || revision.activatedAt() == null) {
                throw invalidChain(tenantId, "Fallback revisions must have one tenant, family, role, and contiguous numbering");
            }
            if (index < fallback.size() - 1) {
                TariffPlan next = fallback.get(index + 1);
                if (!Objects.equals(revision.effectiveTo(), next.effectiveFrom())
                        || revision.status() != TariffPlanStatus.RETIRED
                        || !Objects.equals(revision.effectiveTo(), revision.retiredAt())) {
                    throw invalidChain(tenantId, "Fallback revisions must form a contiguous retired-to-active chain");
                }
            }
        }

        TariffPlan latest = fallback.getLast();
        if (latest.status() != TariffPlanStatus.ACTIVE || latest.effectiveTo() != null
                || latest.retiredAt() != null) {
            throw invalidChain(tenantId, "Fallback family must have exactly one active open-ended revision");
        }
        return latest;
    }

    private static VoltBridgeException invalidChain(UUID tenantId, String reason) {
        return new VoltBridgeException("FALLBACK_REVISION_CHAIN_INVALID",
                reason + " (tenantId=" + tenantId + ")");
    }
}
