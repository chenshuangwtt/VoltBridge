package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.util.List;

/** 定价策略返回的不可变金额及其明细，不携带交易或持久化职责。 */
public record PricingResult(BigDecimal energyKwh, BigDecimal energyFee, BigDecimal serviceFee,
                            BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount,
                            List<BillingLineItem> lineItems) {
    public PricingResult {
        energyKwh = BillingDecimalPolicy.energy(energyKwh); energyFee = BillingDecimalPolicy.money(energyFee);
        serviceFee = BillingDecimalPolicy.money(serviceFee); subtotalAmount = BillingDecimalPolicy.money(subtotalAmount);
        discountAmount = BillingDecimalPolicy.money(discountAmount); totalAmount = BillingDecimalPolicy.money(totalAmount);
        lineItems = List.copyOf(lineItems);
    }
}
