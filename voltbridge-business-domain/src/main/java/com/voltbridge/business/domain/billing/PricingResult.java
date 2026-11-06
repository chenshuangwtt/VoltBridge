package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 不可变计价结果；总额由明细行求和，防止账单头与明细不一致。 */
public record PricingResult(BigDecimal energyKwh, BigDecimal energyFee, BigDecimal serviceFee,
                            BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount,
                            List<BillingLineItem> lineItems, List<PricingSegment> segments,
                            BigDecimal meterReconciliationDeltaKwh) {
    public PricingResult {
        energyKwh = BillingDecimalPolicy.energy(energyKwh);
        energyFee = BillingDecimalPolicy.money(energyFee);
        serviceFee = BillingDecimalPolicy.money(serviceFee);
        subtotalAmount = BillingDecimalPolicy.money(subtotalAmount);
        discountAmount = BillingDecimalPolicy.money(discountAmount);
        totalAmount = BillingDecimalPolicy.money(totalAmount);
        lineItems = List.copyOf(lineItems);
        segments = List.copyOf(segments);
        meterReconciliationDeltaKwh = Objects.requireNonNull(meterReconciliationDeltaKwh);

        BigDecimal lineTotal = lineItems.stream().map(BillingLineItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(BillingDecimalPolicy.MONEY_SCALE);
        BigDecimal lineEnergy = sumLineType(lineItems, BillingLineItemType.ENERGY_FEE);
        BigDecimal lineService = sumLineType(lineItems, BillingLineItemType.SERVICE_FEE);
        if (lineTotal.compareTo(subtotalAmount) != 0 || lineEnergy.compareTo(energyFee) != 0
                || lineService.compareTo(serviceFee) != 0
                || subtotalAmount.subtract(discountAmount).compareTo(totalAmount) != 0) {
            throw new IllegalArgumentException("Pricing totals must equal the rounded line item totals");
        }
    }

    private static BigDecimal sumLineType(List<BillingLineItem> lineItems, BillingLineItemType type) {
        return lineItems.stream().filter(item -> item.type() == type).map(BillingLineItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(BillingDecimalPolicy.MONEY_SCALE);
    }
}
