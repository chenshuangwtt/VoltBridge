package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.FixedRatePricingPolicy;
import com.voltbridge.business.domain.billing.PricingEngine;
import com.voltbridge.business.domain.billing.PricingContext;
import com.voltbridge.business.domain.billing.PricingPolicy;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TimeOfUsePricingPolicy;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 按资费类型选择小型显式策略集合，不引入动态规则引擎。 */
@Component
public class DefaultPricingEngine implements PricingEngine {
    private final Map<PricingType, PricingPolicy> policies;

    public DefaultPricingEngine() {
        PricingPolicy fixed = new FixedRatePricingPolicy();
        PricingPolicy timeOfUse = new TimeOfUsePricingPolicy();
        policies = Map.of(fixed.pricingType(), fixed, timeOfUse.pricingType(), timeOfUse);
    }

    @Override
    public PricingResult calculate(PricingContext context) {
        PricingPolicy policy = policies.get(context.tariffPlan().pricingType());
        if (policy == null) throw new IllegalArgumentException("Unsupported pricing type: " + context.tariffPlan().pricingType());
        return policy.calculate(context);
    }
}
