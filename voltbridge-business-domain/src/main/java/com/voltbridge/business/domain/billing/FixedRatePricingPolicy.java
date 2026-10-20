package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.util.List;

/** 固定电价策略：电费和服务费均按最终规范电量乘以每度单价。 */
public class FixedRatePricingPolicy implements PricingPolicy {
    @Override public PricingType pricingType() { return PricingType.FIXED; }
    @Override public PricingResult calculate(BigDecimal billableEnergyKwh, TariffPlan tariffPlan) {
        if (tariffPlan.pricingType() != pricingType()) throw new IllegalArgumentException("Tariff pricing type mismatch");
        BigDecimal energy = BillingDecimalPolicy.energy(billableEnergyKwh);
        BigDecimal energyFee = BillingDecimalPolicy.money(energy.multiply(tariffPlan.energyPricePerKwh()));
        BigDecimal serviceFee = BillingDecimalPolicy.money(energy.multiply(tariffPlan.serviceFeePerKwh()));
        BigDecimal subtotal = BillingDecimalPolicy.money(energyFee.add(serviceFee));
        BigDecimal discount = BigDecimal.ZERO.setScale(BillingDecimalPolicy.MONEY_SCALE);
        return new PricingResult(energy, energyFee, serviceFee, subtotal, discount, subtotal,
                List.of(new BillingLineItem(BillingLineItemType.ENERGY_FEE, "电能费", energy, "kWh", tariffPlan.energyPricePerKwh(), energyFee),
                        new BillingLineItem(BillingLineItemType.SERVICE_FEE, "充电服务费", energy, "kWh", tariffPlan.serviceFeePerKwh(), serviceFee)));
    }
}
