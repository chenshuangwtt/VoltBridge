package com.voltbridge.business.domain.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 可版本化的资费方案聚合。
 *
 * <p>方案不提供原地改价操作；费率变化应创建新的方案/修订。账单会保存完整费率快照，
 * 因此启用或退役方案都不会改变已完成交易的历史金额。</p>
 */
public class TariffPlan {
    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final String currency;
    private final PricingType pricingType;
    private final BigDecimal energyPricePerKwh;
    private final BigDecimal serviceFeePerKwh;
    private final Instant effectiveFrom;
    private final Instant effectiveTo;
    private final Instant createdAt;
    private TariffPlanStatus status;
    private Instant activatedAt;
    private long version;

    private TariffPlan(UUID id, UUID tenantId, String name, String currency, PricingType pricingType,
                       BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh, Instant effectiveFrom,
                       Instant effectiveTo, TariffPlanStatus status, Instant activatedAt, Instant createdAt,
                       long version) {
        this.id = Objects.requireNonNull(id); this.tenantId = Objects.requireNonNull(tenantId);
        this.name = requireText(name, "name"); this.currency = requireText(currency, "currency");
        this.pricingType = Objects.requireNonNull(pricingType);
        this.energyPricePerKwh = BillingDecimalPolicy.unitPrice(energyPricePerKwh, "energyPricePerKwh");
        this.serviceFeePerKwh = BillingDecimalPolicy.unitPrice(serviceFeePerKwh, "serviceFeePerKwh");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom);
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        this.effectiveTo = effectiveTo; this.status = Objects.requireNonNull(status);
        this.activatedAt = activatedAt; this.createdAt = Objects.requireNonNull(createdAt); this.version = version;
    }

    public static TariffPlan create(UUID tenantId, String name, String currency, BigDecimal energyPricePerKwh,
                                    BigDecimal serviceFeePerKwh, Instant effectiveFrom, Instant effectiveTo, Instant now) {
        return new TariffPlan(UUID.randomUUID(), tenantId, name, currency, PricingType.FIXED, energyPricePerKwh,
                serviceFeePerKwh, effectiveFrom, effectiveTo, TariffPlanStatus.DRAFT, null, now, 0);
    }

    public static TariffPlan restore(UUID id, UUID tenantId, String name, String currency, PricingType pricingType,
                                     BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh, Instant effectiveFrom,
                                     Instant effectiveTo, TariffPlanStatus status, Instant activatedAt, Instant createdAt, long version) {
        return new TariffPlan(id, tenantId, name, currency, pricingType, energyPricePerKwh, serviceFeePerKwh,
                effectiveFrom, effectiveTo, status, activatedAt, createdAt, version);
    }

    public void activate(Instant now) {
        if (status == TariffPlanStatus.RETIRED) throw new IllegalStateException("Retired tariff plan cannot be activated");
        status = TariffPlanStatus.ACTIVE; activatedAt = now;
    }

    public boolean effectiveAt(Instant instant) {
        return status == TariffPlanStatus.ACTIVE && !instant.isBefore(effectiveFrom)
                && (effectiveTo == null || instant.isBefore(effectiveTo));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
    public UUID id() { return id; } public UUID tenantId() { return tenantId; } public String name() { return name; }
    public String currency() { return currency; } public PricingType pricingType() { return pricingType; }
    public BigDecimal energyPricePerKwh() { return energyPricePerKwh; } public BigDecimal serviceFeePerKwh() { return serviceFeePerKwh; }
    public Instant effectiveFrom() { return effectiveFrom; } public Instant effectiveTo() { return effectiveTo; }
    public TariffPlanStatus status() { return status; } public Instant activatedAt() { return activatedAt; }
    public Instant createdAt() { return createdAt; } public long version() { return version; }
}
