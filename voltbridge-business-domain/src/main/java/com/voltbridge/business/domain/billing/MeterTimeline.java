package com.voltbridge.business.domain.billing;

import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.common.exception.VoltBridgeException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 使用交易真实起止表读数和已关联的累计电表样本构建的计费时间线。 */
public final class MeterTimeline {
    private final List<MeterPoint> points;
    private final BigDecimal transactionEnergyKwh;
    private final BigDecimal meterEnergyKwh;
    private final BigDecimal reconciliationDeltaKwh;
    private final boolean required;

    private MeterTimeline(List<MeterPoint> points, BigDecimal transactionEnergyKwh,
                          BigDecimal meterEnergyKwh, BigDecimal reconciliationDeltaKwh,
                          boolean required) {
        this.points = List.copyOf(points);
        this.transactionEnergyKwh = transactionEnergyKwh;
        this.meterEnergyKwh = meterEnergyKwh;
        this.reconciliationDeltaKwh = reconciliationDeltaKwh;
        this.required = required;
    }

    public static MeterTimeline notRequired() {
        return new MeterTimeline(List.of(), null, null, BigDecimal.ZERO, false);
    }

    public static MeterTimeline fromTransaction(ChargingTransaction transaction,
                                                List<ChargingMeterSample> samples) {
        if (transaction.startedAt() == null || transaction.endedAt() == null
                || transaction.energyKwh() == null || transaction.endedAt().isBefore(transaction.startedAt())) {
            throw missingTimeline(transaction.id());
        }

        List<MeterPoint> points = new ArrayList<>();
        if (transaction.startMeterKwh() != null) {
            points.add(new MeterPoint(transaction.startedAt(), transaction.startMeterKwh()));
        }
        samples.stream()
                .filter(sample -> sample.transactionId().equals(transaction.id()))
                .filter(sample -> !sample.occurredAt().isBefore(transaction.startedAt()))
                .filter(sample -> !sample.occurredAt().isAfter(transaction.endedAt()))
                .map(sample -> new MeterPoint(sample.occurredAt(), sample.energyKwh()))
                .forEach(points::add);
        if (transaction.endMeterKwh() != null) {
            points.add(new MeterPoint(transaction.endedAt(), transaction.endMeterKwh()));
        }

        points.sort(Comparator.comparing(MeterPoint::occurredAt));
        points = deduplicateTimestamp(points, transaction.id());
        if (points.size() < 2 || !points.getFirst().occurredAt().equals(transaction.startedAt())
                || !points.getLast().occurredAt().equals(transaction.endedAt())) {
            throw missingTimeline(transaction.id());
        }

        for (int index = 1; index < points.size(); index++) {
            if (points.get(index).energyKwh().compareTo(points.get(index - 1).energyKwh()) < 0) {
                throw new VoltBridgeException("METER_READING_NON_MONOTONIC",
                        "Cumulative meter reading decreased for transaction " + transaction.id());
            }
        }

        BigDecimal meterEnergy = points.getLast().energyKwh().subtract(points.getFirst().energyKwh());
        BigDecimal delta = transaction.energyKwh().subtract(meterEnergy);
        if (delta.abs().compareTo(BillingDecimalPolicy.METER_ENERGY_TOLERANCE) > 0) {
            throw new VoltBridgeException("METER_ENERGY_MISMATCH",
                    "Meter sample energy does not match final transaction energy: " + transaction.id());
        }
        return new MeterTimeline(points, BillingDecimalPolicy.energy(transaction.energyKwh()),
                BillingDecimalPolicy.energy(meterEnergy), delta, true);
    }

    private static List<MeterPoint> deduplicateTimestamp(List<MeterPoint> sorted, UUID transactionId) {
        List<MeterPoint> distinct = new ArrayList<>();
        for (MeterPoint point : sorted) {
            if (!distinct.isEmpty() && distinct.getLast().occurredAt().equals(point.occurredAt())) {
                if (distinct.getLast().energyKwh().compareTo(point.energyKwh()) != 0) {
                    throw new VoltBridgeException("METER_TIMELINE_INVALID",
                            "Conflicting meter readings share one timestamp for transaction " + transactionId);
                }
                continue;
            }
            distinct.add(point);
        }
        return distinct;
    }

    private static VoltBridgeException missingTimeline(UUID transactionId) {
        return new VoltBridgeException("METER_TIMELINE_MISSING",
                "A complete start-to-end meter timeline is required for transaction " + transactionId);
    }

    public List<MeterPoint> points() { return points; }
    public BigDecimal transactionEnergyKwh() { return transactionEnergyKwh; }
    public BigDecimal meterEnergyKwh() { return meterEnergyKwh; }
    public BigDecimal reconciliationDeltaKwh() { return reconciliationDeltaKwh; }
    public boolean required() { return required; }
}
