package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Initializes the required database-backed fallback family when a tenant is created. */
@Service
public class EnsureTenantFallbackTariffUseCase {
    public static final String DEFAULT_NAME = "默认固定资费";
    public static final String DEFAULT_CURRENCY = "CNY";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    public static final BigDecimal DEFAULT_ENERGY_PRICE = new BigDecimal("1.00");
    public static final BigDecimal DEFAULT_SERVICE_FEE = new BigDecimal("0.20");

    private final TariffPlanRepository tariffPlans;
    private final TimeProvider timeProvider;

    public EnsureTenantFallbackTariffUseCase(TariffPlanRepository tariffPlans, TimeProvider timeProvider) {
        this.tariffPlans = tariffPlans;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public TariffPlan execute(UUID tenantId) {
        tariffPlans.lockTenantForTariffLifecycle(tenantId);
        List<TariffPlan> tenantPlans = tariffPlans.findByTenantId(tenantId);
        if (tenantPlans.stream().anyMatch(plan -> plan.role() == TariffRole.FALLBACK)) {
            return FallbackTariffChain.currentRevision(tenantId, tenantPlans);
        }

        var now = timeProvider.now();
        TariffPlan fallback = TariffPlan.createFallback(tenantId, UUID.randomUUID(), DEFAULT_NAME,
                DEFAULT_CURRENCY, DEFAULT_TIMEZONE,
                List.of(new TariffPeriod(1, TariffPeriodType.FLAT, 0, 1440,
                        DEFAULT_ENERGY_PRICE, DEFAULT_SERVICE_FEE)), now, now);
        tariffPlans.save(fallback);
        return fallback;
    }
}
