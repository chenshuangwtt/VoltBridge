package com.voltbridge.business.domain.billing;

import com.voltbridge.common.exception.VoltBridgeException;

import java.math.BigDecimal;
import java.util.List;

/** FIXED 资费使用统一 period 模型中的唯一全天 FLAT 时段。 */
public class FixedRatePricingPolicy implements PricingPolicy {
    @Override
    public PricingType pricingType() {
        return PricingType.FIXED;
    }

    @Override
    public PricingResult calculate(PricingContext context) {
        TariffPlan tariff = context.tariffPlan();
        if (tariff.pricingType() != pricingType()) {
            throw new IllegalArgumentException("Tariff pricing type mismatch");
        }
        BigDecimal energy = context.transaction().energyKwh();
        if (energy == null) {
            throw new VoltBridgeException("BILLABLE_ENERGY_MISSING", "Completed transaction has no final energy");
        }
        energy = BillingDecimalPolicy.energy(energy);
        TariffPeriod period = tariff.periods().getFirst();
        BigDecimal energyFee = BillingDecimalPolicy.money(energy.multiply(period.energyPricePerKwh()));
        BigDecimal serviceFee = BillingDecimalPolicy.money(energy.multiply(period.serviceFeePerKwh()));
        BigDecimal subtotal = BillingDecimalPolicy.money(energyFee.add(serviceFee));
        BigDecimal zero = BigDecimal.ZERO.setScale(BillingDecimalPolicy.MONEY_SCALE);

        PricingSegment segment = new PricingSegment(period.sequenceNo(), period.periodType(),
                period.startMinute(), period.endMinute(), energy, period.energyPricePerKwh(),
                period.serviceFeePerKwh(), energyFee, serviceFee, BigDecimal.ZERO);
        List<BillingLineItem> lineItems = List.of(
                new BillingLineItem(BillingLineItemType.ENERGY_FEE, "电能费（全天固定费率）", energy,
                        "kWh", period.energyPricePerKwh(), energyFee),
                new BillingLineItem(BillingLineItemType.SERVICE_FEE, "充电服务费（全天固定费率）", energy,
                        "kWh", period.serviceFeePerKwh(), serviceFee));
        return new PricingResult(energy, energyFee, serviceFee, subtotal, zero, subtotal,
                lineItems, List.of(segment), BigDecimal.ZERO);
    }
}
