package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffResolver;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.stereotype.Service;

/**
 * MVP 资费解析器：按租户和交易开始时间选择一条已启用且有效的固定资费。
 *
 * <p>此处刻意不实现站点、设备、会员层级；后续解析器可替换而无需修改定价或账单服务。</p>
 */
@Service
public class DefaultTariffResolver implements TariffResolver {
    private final TariffPlanRepository tariffPlans;
    public DefaultTariffResolver(TariffPlanRepository tariffPlans) { this.tariffPlans = tariffPlans; }
    @Override public TariffPlan resolve(ChargingTransaction transaction) {
        if (transaction.startedAt() == null) throw new VoltBridgeException("BILLABLE_ENERGY_MISSING", "Completed transaction has no charging start time");
        return tariffPlans.findEffectiveActive(transaction.tenantId(), transaction.startedAt())
                .orElseThrow(() -> new VoltBridgeException("TARIFF_NOT_FOUND", "No effective active tariff for transaction " + transaction.id()));
    }
}
