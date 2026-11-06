package com.voltbridge.business.domain.billing;

/** 选择与资费类型匹配的策略，避免应用服务感知具体计算公式。 */
public interface PricingEngine {
    PricingResult calculate(PricingContext context);
}
