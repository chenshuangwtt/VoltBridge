package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已完成交易的不可变财务计算结果，不代表支付、退款、结算或发票状态。
 */
public record BillingRecord(UUID id, UUID tenantId, UUID transactionId, UUID tariffPlanId, String currency,
                            BigDecimal energyKwh, BigDecimal energyFee, BigDecimal serviceFee,
                            BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount,
                            BillingStatus status, Instant chargingStartedAt, Instant chargingEndedAt,
                            String pricingSnapshot, List<BillingLineItem> lineItems,
                            Instant createdAt, Instant finalizedAt) {
    public BillingRecord {
        id = Objects.requireNonNull(id); tenantId = Objects.requireNonNull(tenantId); transactionId = Objects.requireNonNull(transactionId);
        tariffPlanId = Objects.requireNonNull(tariffPlanId); currency = Objects.requireNonNull(currency);
        energyKwh = BillingDecimalPolicy.energy(energyKwh); energyFee = BillingDecimalPolicy.money(energyFee);
        serviceFee = BillingDecimalPolicy.money(serviceFee); subtotalAmount = BillingDecimalPolicy.money(subtotalAmount);
        discountAmount = BillingDecimalPolicy.money(discountAmount); totalAmount = BillingDecimalPolicy.money(totalAmount);
        status = Objects.requireNonNull(status); pricingSnapshot = Objects.requireNonNull(pricingSnapshot);
        lineItems = List.copyOf(lineItems); createdAt = Objects.requireNonNull(createdAt); finalizedAt = Objects.requireNonNull(finalizedAt);
    }

    public static BillingRecord finalized(UUID tenantId, UUID transactionId, TariffPlan tariffPlan,
                                          Instant chargingStartedAt, Instant chargingEndedAt, PricingResult result,
                                          String pricingSnapshot, Instant now) {
        return new BillingRecord(UUID.randomUUID(), tenantId, transactionId, tariffPlan.id(), tariffPlan.currency(),
                result.energyKwh(), result.energyFee(), result.serviceFee(), result.subtotalAmount(), result.discountAmount(),
                result.totalAmount(), BillingStatus.FINALIZED, chargingStartedAt, chargingEndedAt, pricingSnapshot,
                result.lineItems(), now, now);
    }
}
