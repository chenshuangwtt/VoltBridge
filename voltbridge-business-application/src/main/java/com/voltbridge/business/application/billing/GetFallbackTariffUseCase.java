package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Resolves the tenant's fallback revision at a requested instant, defaulting to platform time. */
@Service
public class GetFallbackTariffUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(GetFallbackTariffUseCase.class);

    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public GetFallbackTariffUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
        this.tariffPlans = tariffPlans;
        this.timeProvider = timeProvider;
    }

    public TariffPlan execute(UUID tenantId, Instant effectiveAt) {
        Instant resolvedAt = effectiveAt == null ? timeProvider.now() : effectiveAt;
        List<TariffPlan> matches = tariffPlans.findEffectiveAt(tenantId, TariffRole.FALLBACK, resolvedAt);
        if (matches.isEmpty()) {
            throw new VoltBridgeException("FALLBACK_TARIFF_NOT_FOUND",
                    "No fallback tariff revision covers tenant " + tenantId + " at " + resolvedAt);
        }
        if (matches.size() > 1) {
            LOGGER.error("Multiple fallback revisions match: tenantId={}, effectiveAt={}, tariffPlanIds={}",
                    tenantId, resolvedAt, matches.stream().map(TariffPlan::id).toList());
            throw new VoltBridgeException("FALLBACK_TARIFF_AMBIGUOUS",
                    "Multiple fallback revisions match tenant " + tenantId + " at " + resolvedAt);
        }
        return matches.getFirst();
    }
}
