package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.util.Objects;

/** 不可变账单项目，保留数量、单位价格和金额以便解释最终账单。 */
public record BillingLineItem(BillingLineItemType type, String description, BigDecimal quantity,
                              String unit, BigDecimal unitPrice, BigDecimal amount) {
    public BillingLineItem {
        type = Objects.requireNonNull(type); description = Objects.requireNonNull(description);
        quantity = BillingDecimalPolicy.energy(quantity); unit = Objects.requireNonNull(unit);
        unitPrice = BillingDecimalPolicy.unitPrice(unitPrice, "unitPrice"); amount = BillingDecimalPolicy.money(amount);
    }
}
