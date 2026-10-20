package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.FixedRatePricingPolicy;
import com.voltbridge.business.domain.billing.PricingEngine;
import com.voltbridge.business.domain.billing.PricingPolicy;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.TariffPlan;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** 按资费类型选择小型显式策略集合，不引入动态规则引擎。 */
@Component
public class DefaultPricingEngine implements PricingEngine {
    private final Map<com.voltbridge.business.domain.billing.PricingType, PricingPolicy> policies;

    public DefaultPricingEngine() {
        PricingPolicy fixed = new FixedRatePricingPolicy();
        policies = Map.of(fixed.pricingType(), fixed);
    }

    @Override
    public PricingResult calculate(BigDecimal billableEnergyKwh, TariffPlan tariffPlan) {
        PricingPolicy policy = policies.get(tariffPlan.pricingType());
        if (policy == null) throw new IllegalArgumentException("Unsupported pricing type: " + tariffPlan.pricingType());
        return policy.calculate(billableEnergyKwh, tariffPlan);
    }
}
