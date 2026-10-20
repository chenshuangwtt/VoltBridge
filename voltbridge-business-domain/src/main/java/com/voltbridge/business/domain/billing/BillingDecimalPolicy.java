package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Billing MVP 的统一精度策略。
 *
 * <p>电量保留 6 位 kWh，单价保留 6 位 CNY/kWh，金额统一按 HALF_UP 保留 2 位 CNY；
 * 每个明细项目先独立舍入，再相加得到总额，避免不同调用点散落 setScale 规则。</p>
 */
public final class BillingDecimalPolicy {
    public static final int ENERGY_SCALE = 6;
    public static final int UNIT_PRICE_SCALE = 6;
    public static final int MONEY_SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private BillingDecimalPolicy() {
    }

    public static BigDecimal energy(BigDecimal value) {
        return requiredNonNegative(value, "energyKwh").setScale(ENERGY_SCALE, ROUNDING_MODE);
    }

    public static BigDecimal unitPrice(BigDecimal value, String field) {
        return requiredNonNegative(value, field).setScale(UNIT_PRICE_SCALE, ROUNDING_MODE);
    }

    public static BigDecimal money(BigDecimal value) {
        return requiredNonNegative(value, "amount").setScale(MONEY_SCALE, ROUNDING_MODE);
    }

    private static BigDecimal requiredNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }
}
