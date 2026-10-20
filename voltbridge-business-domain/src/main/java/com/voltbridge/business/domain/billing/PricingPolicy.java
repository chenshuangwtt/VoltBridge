package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;

/** 单一计价类型的领域策略；未来可新增 TimeOfUsePricingPolicy 而无需修改 Billing 服务。 */
public interface PricingPolicy {
    PricingType pricingType();
    PricingResult calculate(BigDecimal billableEnergyKwh, TariffPlan tariffPlan);
}
