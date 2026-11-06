package com.voltbridge.business.domain.billing;

import com.voltbridge.common.exception.VoltBridgeException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.LinkedHashMap;

/** 按真实相邻累计电表读数分段；只对采样区间内部的资费边界进行局部线性插值。 */
public class TimeOfUsePricingPolicy implements PricingPolicy {
    private static final int INTERPOLATION_SCALE = 12;

    @Override
    public PricingType pricingType() {
        return PricingType.TIME_OF_USE;
    }

    @Override
    public PricingResult calculate(PricingContext context) {
        TariffPlan tariff = context.tariffPlan();
        if (tariff.pricingType() != pricingType()) {
            throw new IllegalArgumentException("Tariff pricing type mismatch");
        }
        MeterTimeline timeline = context.meterTimeline();
        if (!timeline.required() || timeline.points().size() < 2) {
            throw new VoltBridgeException("METER_TIMELINE_MISSING", "A real meter timeline is required for time-of-use billing");
        }

        // Preserve first-use order so an interval crossing midnight keeps its chronological
        // period sequence (for example, late-day valley followed by next-day valley).
        Map<Integer, BigDecimal> energyByPeriod = new LinkedHashMap<>();
        for (int index = 1; index < timeline.points().size(); index++) {
            MeterPoint left = timeline.points().get(index - 1);
            MeterPoint right = timeline.points().get(index);
            distributeInterval(left, right, tariff, energyByPeriod);
        }

        List<TariffPeriod> matchedPeriods = new ArrayList<>();
        List<BigDecimal> normalizedEnergy = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> entry : energyByPeriod.entrySet()) {
            TariffPeriod period = tariff.periods().get(entry.getKey() - 1);
            matchedPeriods.add(period);
            normalizedEnergy.add(BillingDecimalPolicy.energy(entry.getValue()));
        }
        if (normalizedEnergy.isEmpty()) {
            return zeroEnergyResult(timeline);
        }

        BigDecimal assignedEnergy = normalizedEnergy.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residual = timeline.transactionEnergyKwh().subtract(assignedEnergy)
                .setScale(BillingDecimalPolicy.ENERGY_SCALE, BillingDecimalPolicy.ROUNDING_MODE);
        if (residual.abs().compareTo(BillingDecimalPolicy.METER_ENERGY_TOLERANCE) > 0) {
            throw new VoltBridgeException("METER_ENERGY_MISMATCH", "Rounded period energy does not match final transaction energy");
        }
        int last = normalizedEnergy.size() - 1;
        normalizedEnergy.set(last, normalizedEnergy.get(last).add(residual));
        if (normalizedEnergy.get(last).signum() < 0) {
            throw new VoltBridgeException("METER_ENERGY_MISMATCH", "Meter reconciliation would create negative period energy");
        }

        List<PricingSegment> segments = new ArrayList<>();
        List<BillingLineItem> lineItems = new ArrayList<>();
        BigDecimal totalEnergyFee = BigDecimal.ZERO;
        BigDecimal totalServiceFee = BigDecimal.ZERO;
        for (int index = 0; index < matchedPeriods.size(); index++) {
            TariffPeriod period = matchedPeriods.get(index);
            BigDecimal energy = normalizedEnergy.get(index);
            BigDecimal energyFee = BillingDecimalPolicy.money(energy.multiply(period.energyPricePerKwh()));
            BigDecimal serviceFee = BillingDecimalPolicy.money(energy.multiply(period.serviceFeePerKwh()));
            BigDecimal adjustment = index == last ? residual : BigDecimal.ZERO.setScale(BillingDecimalPolicy.ENERGY_SCALE);
            segments.add(new PricingSegment(period.sequenceNo(), period.periodType(), period.startMinute(),
                    period.endMinute(), energy, period.energyPricePerKwh(), period.serviceFeePerKwh(),
                    energyFee, serviceFee, adjustment));
            String window = period.startTimeLabel() + "–" + period.endTimeLabel();
            lineItems.add(new BillingLineItem(BillingLineItemType.ENERGY_FEE,
                    label(period.periodType()) + "段电费（" + window + "）", energy, "kWh",
                    period.energyPricePerKwh(), energyFee));
            lineItems.add(new BillingLineItem(BillingLineItemType.SERVICE_FEE,
                    label(period.periodType()) + "段服务费（" + window + "）", energy, "kWh",
                    period.serviceFeePerKwh(), serviceFee));
            totalEnergyFee = totalEnergyFee.add(energyFee);
            totalServiceFee = totalServiceFee.add(serviceFee);
        }

        totalEnergyFee = BillingDecimalPolicy.money(totalEnergyFee);
        totalServiceFee = BillingDecimalPolicy.money(totalServiceFee);
        BigDecimal subtotal = BillingDecimalPolicy.money(totalEnergyFee.add(totalServiceFee));
        BigDecimal zeroMoney = BigDecimal.ZERO.setScale(BillingDecimalPolicy.MONEY_SCALE);
        BigDecimal reconciliation = timeline.reconciliationDeltaKwh().add(residual);
        return new PricingResult(timeline.transactionEnergyKwh(), totalEnergyFee, totalServiceFee,
                subtotal, zeroMoney, subtotal, lineItems, segments, reconciliation);
    }

    private void distributeInterval(MeterPoint left, MeterPoint right, TariffPlan tariff,
                                    Map<Integer, BigDecimal> energyByPeriod) {
        BigDecimal intervalEnergy = right.energyKwh().subtract(left.energyKwh());
        if (intervalEnergy.signum() == 0) return;
        List<Instant> cuts = new ArrayList<>();
        cuts.add(left.occurredAt());
        cuts.addAll(boundariesBetween(left.occurredAt(), right.occurredAt(), tariff));
        cuts.add(right.occurredAt());
        Duration totalDuration = Duration.between(left.occurredAt(), right.occurredAt());
        BigDecimal totalNanos = durationNanos(totalDuration);
        BigDecimal distributed = BigDecimal.ZERO;
        for (int index = 1; index < cuts.size(); index++) {
            Instant segmentStart = cuts.get(index - 1);
            Instant segmentEnd = cuts.get(index);
            BigDecimal segmentEnergy;
            if (index == cuts.size() - 1) {
                segmentEnergy = intervalEnergy.subtract(distributed);
            } else {
                BigDecimal segmentNanos = durationNanos(Duration.between(segmentStart, segmentEnd));
                segmentEnergy = intervalEnergy.multiply(segmentNanos)
                        .divide(totalNanos, INTERPOLATION_SCALE, RoundingMode.HALF_UP);
                distributed = distributed.add(segmentEnergy);
            }
            if (segmentEnergy.signum() < 0) {
                throw new VoltBridgeException("METER_ENERGY_MISMATCH", "Meter interpolation produced negative energy");
            }
            TariffPeriod period = periodAt(midpoint(segmentStart, segmentEnd), tariff);
            energyByPeriod.merge(period.sequenceNo(), segmentEnergy, BigDecimal::add);
        }
    }

    private List<Instant> boundariesBetween(Instant start, Instant end, TariffPlan tariff) {
        ZoneId zone = tariff.zoneId();
        LocalDate firstDate = start.atZone(zone).toLocalDate();
        LocalDate lastDate = end.atZone(zone).toLocalDate();
        TreeSet<Instant> boundaries = new TreeSet<>();
        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            for (TariffPeriod period : tariff.periods()) {
                LocalDateTime localBoundary = date.atStartOfDay().plusMinutes(period.startMinute());
                for (Instant instant : resolveLocalBoundary(localBoundary, zone)) {
                    if (instant.isAfter(start) && instant.isBefore(end)) boundaries.add(instant);
                }
            }
        }
        return List.copyOf(boundaries);
    }

    private List<Instant> resolveLocalBoundary(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            ZoneOffsetTransition transition = zone.getRules().getTransition(local);
            return List.of(transition.getInstant());
        }
        return offsets.stream().map(local::toInstant).toList();
    }

    private TariffPeriod periodAt(Instant instant, TariffPlan tariff) {
        var local = instant.atZone(tariff.zoneId());
        int minute = local.getHour() * 60 + local.getMinute();
        return tariff.periods().stream()
                .filter(period -> period.startMinute() <= minute && minute < period.endMinute())
                .findFirst()
                .orElseThrow(() -> new VoltBridgeException("TARIFF_PERIOD_COVERAGE_INVALID",
                        "No tariff period covers local minute " + minute));
    }

    private Instant midpoint(Instant start, Instant end) {
        return start.plusNanos(Duration.between(start, end).toNanos() / 2);
    }

    private BigDecimal durationNanos(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).movePointRight(9)
                .add(BigDecimal.valueOf(duration.getNano()));
    }

    private PricingResult zeroEnergyResult(MeterTimeline timeline) {
        BigDecimal zeroEnergy = BigDecimal.ZERO.setScale(BillingDecimalPolicy.ENERGY_SCALE);
        BigDecimal zeroMoney = BigDecimal.ZERO.setScale(BillingDecimalPolicy.MONEY_SCALE);
        return new PricingResult(zeroEnergy, zeroMoney, zeroMoney, zeroMoney, zeroMoney,
                zeroMoney, List.of(), List.of(), timeline.reconciliationDeltaKwh());
    }

    private String label(TariffPeriodType type) {
        return switch (type) {
            case PEAK -> "峰";
            case FLAT -> "平";
            case VALLEY -> "谷";
        };
    }
}
