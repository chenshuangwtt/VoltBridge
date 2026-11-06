package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffResolver;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.common.exception.VoltBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves the one Scheduled plan at transaction start, then falls back to the tenant safety tariff.
 *
 * <p>此处刻意不实现站点、设备、会员层级；后续解析器可替换而无需修改定价或账单服务。</p>
 */
@Service
public class DefaultTariffResolver implements TariffResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTariffResolver.class);
    private final TariffPlanRepository tariffPlans;
    public DefaultTariffResolver(TariffPlanRepository tariffPlans) { this.tariffPlans = tariffPlans; }
    @Override public TariffPlan resolve(ChargingTransaction transaction) {
        if (transaction.startedAt() == null) throw new VoltBridgeException("BILLABLE_ENERGY_MISSING", "Completed transaction has no charging start time");
        List<TariffPlan> scheduled = tariffPlans.findEffectiveAt(
                transaction.tenantId(), TariffRole.SCHEDULED, transaction.startedAt());
        if (scheduled.size() > 1) {
            List<java.util.UUID> tariffIds = scheduled.stream().map(TariffPlan::id).toList();
            LOGGER.error("Multiple active tariffs match billing time: tenantId={}, transactionId={}, effectiveAt={}, tariffPlanIds={}",
                    transaction.tenantId(), transaction.id(), transaction.startedAt(), tariffIds);
            throw new VoltBridgeException("TARIFF_EFFECTIVE_RANGE_AMBIGUOUS",
                    "Multiple active tariff plans match tenant " + transaction.tenantId()
                            + " at " + transaction.startedAt() + ": " + tariffIds);
        }
        if (scheduled.size() == 1) return scheduled.getFirst();

        List<TariffPlan> fallback = tariffPlans.findEffectiveAt(
                transaction.tenantId(), TariffRole.FALLBACK, transaction.startedAt());
        if (fallback.isEmpty()) {
            throw new VoltBridgeException("FALLBACK_TARIFF_NOT_FOUND",
                    "No fallback tariff revision covers transaction " + transaction.id()
                            + " at " + transaction.startedAt());
        }
        if (fallback.size() > 1) {
            List<java.util.UUID> tariffIds = fallback.stream().map(TariffPlan::id).toList();
            LOGGER.error("Multiple fallback tariffs match billing time: tenantId={}, transactionId={}, effectiveAt={}, tariffPlanIds={}",
                    transaction.tenantId(), transaction.id(), transaction.startedAt(), tariffIds);
            throw new VoltBridgeException("FALLBACK_TARIFF_AMBIGUOUS",
                    "Multiple fallback tariff revisions match tenant " + transaction.tenantId()
                            + " at " + transaction.startedAt() + ": " + tariffIds);
        }
        return fallback.getFirst();
    }
}
