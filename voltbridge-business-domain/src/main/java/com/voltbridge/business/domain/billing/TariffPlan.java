package com.voltbridge.business.domain.billing;

import com.voltbridge.common.exception.VoltBridgeException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 不可原地改价的资费业务版本；revision 与乐观锁 version 分别表达业务版本和存储并发版本。 */
public class TariffPlan {
    private final UUID id;
    private final UUID familyId;
    private final int revision;
    private final UUID tenantId;
    private final TariffRole role;
    private final String name;
    private final String currency;
    private final PricingType pricingType;
    private final String timezone;
    private final List<TariffPeriod> periods;
    private final Instant effectiveFrom;
    private Instant effectiveTo;
    private final Instant createdAt;
    private TariffPlanStatus status;
    private Instant activatedAt;
    private Instant retiredAt;
    private long version;

    private TariffPlan(UUID id, UUID familyId, int revision, UUID tenantId, TariffRole role,
                       String name, String currency,
                       PricingType pricingType, String timezone, List<TariffPeriod> periods,
                       Instant effectiveFrom, Instant effectiveTo, TariffPlanStatus status,
                       Instant activatedAt, Instant retiredAt, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.familyId = Objects.requireNonNull(familyId);
        if (revision < 1) throw new VoltBridgeException("TARIFF_REVISION_INVALID", "Tariff revision must be positive");
        this.revision = revision;
        this.tenantId = Objects.requireNonNull(tenantId);
        this.role = Objects.requireNonNull(role);
        this.name = requireText(name, "name");
        this.currency = requireText(currency, "currency");
        this.pricingType = Objects.requireNonNull(pricingType);
        if (role == TariffRole.FALLBACK && pricingType != PricingType.FIXED) {
            throw new VoltBridgeException("FALLBACK_TARIFF_MUST_BE_FIXED", "Fallback tariffs must use FIXED pricing");
        }
        this.timezone = validateTimezone(timezone);
        this.periods = validatePeriods(pricingType, periods);
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom);
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new VoltBridgeException("TARIFF_EFFECTIVE_RANGE_INVALID", "effectiveTo must be after effectiveFrom");
        }
        this.effectiveTo = effectiveTo;
        this.status = Objects.requireNonNull(status);
        this.activatedAt = activatedAt;
        this.retiredAt = retiredAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.version = version;
    }

    public static TariffPlan create(UUID tenantId, String name, String currency, PricingType pricingType,
                                    String timezone, List<TariffPeriod> periods, Instant effectiveFrom,
                                    Instant effectiveTo, Instant now) {
        return new TariffPlan(UUID.randomUUID(), UUID.randomUUID(), 1, tenantId, TariffRole.SCHEDULED, name, currency,
                pricingType, timezone, periods, effectiveFrom, effectiveTo,
                TariffPlanStatus.DRAFT, null, null, now, 0);
    }

    public TariffPlan nextRevision(int nextRevision, String name, String currency, PricingType nextPricingType,
                                   String nextTimezone, List<TariffPeriod> nextPeriods,
                                   Instant nextEffectiveFrom, Instant nextEffectiveTo, Instant now) {
        return new TariffPlan(UUID.randomUUID(), familyId, nextRevision, tenantId, role, name, currency,
                nextPricingType, nextTimezone, nextPeriods, nextEffectiveFrom, nextEffectiveTo,
                TariffPlanStatus.DRAFT, null, null, now, 0);
    }

    public static TariffPlan createFallback(UUID tenantId, UUID familyId, String name, String currency,
                                            String timezone, List<TariffPeriod> periods,
                                            Instant effectiveFrom, Instant now) {
        return new TariffPlan(UUID.randomUUID(), familyId, 1, tenantId, TariffRole.FALLBACK, name, currency,
                PricingType.FIXED, timezone, periods, effectiveFrom, null,
                TariffPlanStatus.ACTIVE, now, null, now, 0);
    }

    public static TariffPlan restore(UUID id, UUID familyId, int revision, UUID tenantId, TariffRole role, String name,
                                     String currency, PricingType pricingType, String timezone,
                                     List<TariffPeriod> periods, Instant effectiveFrom, Instant effectiveTo,
                                     TariffPlanStatus status, Instant activatedAt, Instant retiredAt,
                                     Instant createdAt, long version) {
        return new TariffPlan(id, familyId, revision, tenantId, role, name, currency, pricingType, timezone,
                periods, effectiveFrom, effectiveTo, status, activatedAt, retiredAt, createdAt, version);
    }

    public void activate(Instant now) {
        if (status == TariffPlanStatus.ACTIVE) return;
        if (status == TariffPlanStatus.RETIRED) {
            throw new VoltBridgeException("TARIFF_RETIRED", "Retired tariff plan cannot be activated");
        }
        if (status != TariffPlanStatus.DRAFT) {
            throw new VoltBridgeException("TARIFF_INVALID_STATE", "Only a draft tariff plan can be activated");
        }
        status = TariffPlanStatus.ACTIVE;
        activatedAt = Objects.requireNonNull(now);
    }

    public void retire(Instant now) {
        if (status == TariffPlanStatus.RETIRED) return;
        if (status != TariffPlanStatus.ACTIVE) {
            throw new VoltBridgeException("TARIFF_INVALID_STATE", "Only an active tariff plan can be retired");
        }
        retiredAt = Objects.requireNonNull(now);
        if (effectiveFrom.isBefore(now) && (effectiveTo == null || now.isBefore(effectiveTo))) {
            effectiveTo = now;
        }
        status = TariffPlanStatus.RETIRED;
    }

    public boolean effectiveAt(Instant instant) {
        boolean historicallyAvailable = status == TariffPlanStatus.ACTIVE
                || (status == TariffPlanStatus.RETIRED && retiredAt != null && instant.isBefore(retiredAt));
        return activatedAt != null && historicallyAvailable && effectiveRange().contains(instant);
    }

    /** Closes the current open-ended FALLBACK revision as part of an atomic chain switch. */
    public void closeFallbackAt(Instant switchAt) {
        if (role != TariffRole.FALLBACK || status != TariffPlanStatus.ACTIVE) {
            throw new VoltBridgeException("FALLBACK_REVISION_INVALID_STATE", "Only an active fallback revision can be closed");
        }
        if (effectiveTo != null || !switchAt.isAfter(effectiveFrom)) {
            throw new VoltBridgeException("FALLBACK_SWITCH_TIME_INVALID", "Fallback switch time must follow the current open revision start");
        }
        effectiveTo = switchAt;
        retiredAt = switchAt;
        status = TariffPlanStatus.RETIRED;
    }

    public TariffEffectiveRange effectiveRange() {
        return new TariffEffectiveRange(effectiveFrom, effectiveTo);
    }

    private static String validateTimezone(String value) {
        String timezone = requireText(value, "timezone");
        try {
            ZoneId.of(timezone);
            return timezone;
        } catch (DateTimeException invalidTimezone) {
            throw new VoltBridgeException("TARIFF_TIMEZONE_INVALID", "Invalid tariff timezone: " + timezone);
        }
    }

    private static List<TariffPeriod> validatePeriods(PricingType pricingType, List<TariffPeriod> values) {
        if (values == null || values.isEmpty()) {
            throw new VoltBridgeException("TARIFF_PERIOD_COVERAGE_INVALID", "Tariff periods must cover the full day");
        }
        List<TariffPeriod> sorted = values.stream().sorted(Comparator.comparingInt(TariffPeriod::sequenceNo)).toList();
        for (int index = 0; index < sorted.size(); index++) {
            TariffPeriod current = sorted.get(index);
            if (current.sequenceNo() != index + 1) {
                throw new VoltBridgeException("TARIFF_PERIOD_INVALID", "Tariff period sequence numbers must be continuous from 1");
            }
            if (index > 0) {
                TariffPeriod previous = sorted.get(index - 1);
                if (previous.endMinute() < current.startMinute()) {
                    throw new VoltBridgeException("TARIFF_PERIOD_GAP", "Tariff periods contain a gap");
                }
                if (previous.endMinute() > current.startMinute()) {
                    throw new VoltBridgeException("TARIFF_PERIOD_OVERLAP", "Tariff periods overlap");
                }
            }
        }
        if (sorted.getFirst().startMinute() != 0 || sorted.getLast().endMinute() != 1440) {
            throw new VoltBridgeException("TARIFF_PERIOD_COVERAGE_INVALID", "Tariff periods must cover 00:00 through 24:00");
        }
        if (pricingType == PricingType.FIXED
                && (sorted.size() != 1 || sorted.getFirst().periodType() != TariffPeriodType.FLAT)) {
            throw new VoltBridgeException("TARIFF_PERIOD_INVALID", "FIXED tariffs require one FLAT period from 00:00 to 24:00");
        }
        return List.copyOf(sorted);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new VoltBridgeException("TARIFF_PLAN_INVALID", field + " must not be blank");
        }
        return value.trim();
    }

    public UUID id() { return id; }
    public UUID familyId() { return familyId; }
    public int revision() { return revision; }
    public UUID tenantId() { return tenantId; }
    public TariffRole role() { return role; }
    public String name() { return name; }
    public String currency() { return currency; }
    public PricingType pricingType() { return pricingType; }
    public String timezone() { return timezone; }
    public ZoneId zoneId() { return ZoneId.of(timezone); }
    public List<TariffPeriod> periods() { return periods; }
    public Instant effectiveFrom() { return effectiveFrom; }
    public Instant effectiveTo() { return effectiveTo; }
    public TariffPlanStatus status() { return status; }
    public Instant activatedAt() { return activatedAt; }
    public Instant retiredAt() { return retiredAt; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
