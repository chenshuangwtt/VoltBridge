package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.ChargingMeterSample;
import com.voltbridge.business.domain.billing.MeterTimeline;
import com.voltbridge.business.domain.billing.PricingContext;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffEffectiveRange;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.business.domain.billing.TimeOfUsePricingPolicy;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceProtocol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TariffAndTimeOfUseBillingTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void validatesFixedAndFullDayTimeOfUsePeriods() {
        assertThat(fixedPlan(UUID.randomUUID(), new BigDecimal("1.00"), new BigDecimal("0.20"))
                .periods()).hasSize(1);
        assertThat(timeOfUsePlan(UUID.randomUUID(), NOW, null).periods()).hasSize(5);
        assertThat(period(1, TariffPeriodType.VALLEY, 0, 420, "0.60", "0.20").endTimeLabel())
                .isEqualTo("07:00");
        assertThat(period(2, TariffPeriodType.PEAK, 420, 600, "1.20", "0.30").startTimeLabel())
                .isEqualTo("07:00");
        assertThat(period(2, TariffPeriodType.PEAK, 420, 600, "1.20", "0.30").endTimeLabel())
                .isEqualTo("10:00");

        assertFailure("TARIFF_PERIOD_INVALID", () -> create(PricingType.FIXED, List.of(
                period(1, TariffPeriodType.FLAT, 0, 720, "1", "0"),
                period(2, TariffPeriodType.FLAT, 720, 1440, "1", "0"))));
        assertFailure("TARIFF_PERIOD_COVERAGE_INVALID", () -> create(PricingType.TIME_OF_USE,
                List.of(period(1, TariffPeriodType.VALLEY, 1, 1440, "1", "0"))));
        assertFailure("TARIFF_PERIOD_COVERAGE_INVALID", () -> create(PricingType.TIME_OF_USE,
                List.of(period(1, TariffPeriodType.VALLEY, 0, 1439, "1", "0"))));
        assertFailure("TARIFF_PERIOD_GAP", () -> create(PricingType.TIME_OF_USE, List.of(
                period(1, TariffPeriodType.VALLEY, 0, 420, "1", "0"),
                period(2, TariffPeriodType.PEAK, 480, 1440, "1", "0"))));
        assertFailure("TARIFF_PERIOD_OVERLAP", () -> create(PricingType.TIME_OF_USE, List.of(
                period(1, TariffPeriodType.VALLEY, 0, 600, "1", "0"),
                period(2, TariffPeriodType.PEAK, 599, 1440, "1", "0"))));
        assertFailure("TARIFF_PERIOD_PRICE_INVALID", () -> period(1, TariffPeriodType.FLAT, 0, 1440, "-0.01", "0"));
        assertFailure("TARIFF_TIMEZONE_INVALID", () -> TariffPlan.create(UUID.randomUUID(), "bad zone", "CNY",
                PricingType.FIXED, "Mars/Olympus", List.of(period(1, TariffPeriodType.FLAT, 0, 1440, "1", "0")),
                NOW, null, NOW));
    }

    @Test
    void createsImmutableFamilyRevisionsAndRejectsActiveRangeOverlap() {
        InMemoryTariffs repository = new InMemoryTariffs();
        TariffPlan original = fixedPlan(UUID.randomUUID(), new BigDecimal("0.80"), new BigDecimal("0.20"));
        repository.save(original);
        assertThat(original.revision()).isOne();

        CreateTariffRevisionUseCase revisionUseCase = new CreateTariffRevisionUseCase(repository, () -> NOW);
        TariffPlan next = revisionUseCase.execute(original.id(), "Fixed V2", "CNY", PricingType.FIXED,
                "Asia/Shanghai", List.of(period(1, TariffPeriodType.FLAT, 0, 1440, "1.10", "0.25")),
                NOW.plusSeconds(3600), null);
        assertThat(next.revision()).isEqualTo(2);
        assertThat(next.familyId()).isEqualTo(original.familyId());
        assertThat(next.status()).isEqualTo(TariffPlanStatus.DRAFT);
        assertThat(original.periods().getFirst().energyPricePerKwh()).isEqualByComparingTo("0.80");
        assertThat(original.status()).isEqualTo(TariffPlanStatus.DRAFT);

        UUID overlapTenant = UUID.randomUUID();
        TariffPlan active = fixedPlan(overlapTenant, BigDecimal.ONE, BigDecimal.ZERO,
                NOW, NOW.plusSeconds(3600));
        repository.save(active);
        ActivateTariffPlanUseCase activate = new ActivateTariffPlanUseCase(repository, () -> NOW);
        activate.execute(active.id());
        TariffPlan overlapping = fixedPlan(overlapTenant, BigDecimal.ONE, BigDecimal.ZERO,
                NOW.plusSeconds(1800), NOW.plusSeconds(7200));
        repository.save(overlapping);
        assertFailure("TARIFF_EFFECTIVE_RANGE_CONFLICT", () -> activate.execute(overlapping.id()));

        TariffPlan adjacent = fixedPlan(overlapTenant, BigDecimal.ONE, BigDecimal.ZERO,
                NOW.plusSeconds(3600), null);
        repository.save(adjacent);
        activate.execute(adjacent.id());
        assertThat(adjacent.status()).isEqualTo(TariffPlanStatus.ACTIVE);

        adjacent.retire(NOW);
        assertFailure("TARIFF_RETIRED", () -> adjacent.activate(NOW));
    }

    @Test
    void timeOfUseSplitsRealMeterReadingsAcrossPeakBoundaryAndConservesEnergy() {
        Instant start = local("2026-11-01T06:50:00");
        Instant end = local("2026-11-01T07:10:00");
        ChargingTransaction transaction = completedTransaction(start, end,
                new BigDecimal("100.000000"), new BigDecimal("100.060000"), new BigDecimal("0.060000"));
        TariffPlan tariff = timeOfUsePlan(transaction.tenantId(), NOW, null);
        MeterTimeline timeline = MeterTimeline.fromTransaction(transaction, List.of());

        PricingResult result = new TimeOfUsePricingPolicy().calculate(new PricingContext(transaction, tariff, timeline));

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments()).extracting(segment -> segment.periodType().name())
                .containsExactly("VALLEY", "PEAK");
        assertThat(result.segments()).extracting(segment -> segment.energyKwh().toPlainString())
                .containsExactly("0.030000", "0.030000");
        assertThat(result.segments().stream().map(segment -> segment.energyKwh())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(transaction.energyKwh());
        assertThat(result.energyFee()).isEqualByComparingTo("0.06");
        assertThat(result.serviceFee()).isEqualByComparingTo("0.02");
        assertThat(result.lineItems()).hasSize(4);
        assertThat(result.totalAmount()).isEqualByComparingTo("0.08");
    }

    @Test
    void timeOfUseSplitsLongMeterIntervalAtMultipleBoundariesAndMidnight() {
        Instant longStart = local("2026-11-01T06:50:00");
        Instant longEnd = local("2026-11-01T10:10:00");
        ChargingTransaction longTransaction = completedTransaction(longStart, longEnd,
                new BigDecimal("10"), new BigDecimal("13.3"), new BigDecimal("3.3"));
        PricingResult longResult = new TimeOfUsePricingPolicy().calculate(new PricingContext(longTransaction,
                timeOfUsePlan(longTransaction.tenantId(), NOW, null), MeterTimeline.fromTransaction(longTransaction, List.of())));
        assertThat(longResult.segments()).hasSize(3);
        assertThat(longResult.segments()).extracting(segment -> segment.periodType().name())
                .containsExactly("VALLEY", "PEAK", "FLAT");
        assertThat(longResult.segments().stream().map(segment -> segment.energyKwh())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("3.300000");

        Instant midnightStart = local("2026-11-01T23:50:00");
        Instant midnightEnd = local("2026-11-02T00:20:00");
        ChargingTransaction midnightTransaction = completedTransaction(midnightStart, midnightEnd,
                new BigDecimal("20"), new BigDecimal("21"), BigDecimal.ONE);
        PricingResult midnightResult = new TimeOfUsePricingPolicy().calculate(new PricingContext(midnightTransaction,
                timeOfUsePlan(midnightTransaction.tenantId(), NOW, null),
                MeterTimeline.fromTransaction(midnightTransaction, List.of())));
        assertThat(midnightResult.segments()).hasSize(2);
        assertThat(midnightResult.segments()).extracting(segment -> segment.sequenceNo()).containsExactly(5, 1);
        assertThat(midnightResult.segments().stream().map(segment -> segment.energyKwh())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1.000000");
    }

    @Test
    void meterTimelineSortsSamplesAndRejectsMissingNonMonotonicOrMismatchedEvidence() {
        Instant start = local("2026-11-01T06:00:00");
        Instant middle = local("2026-11-01T06:30:00");
        Instant end = local("2026-11-01T07:00:00");
        ChargingTransaction transaction = completedTransaction(start, end,
                new BigDecimal("5"), new BigDecimal("6"), BigDecimal.ONE);
        ChargingMeterSample later = sample(transaction.id(), end.minusSeconds(60), "5.9");
        ChargingMeterSample earlier = sample(transaction.id(), middle, "5.5");
        MeterTimeline timeline = MeterTimeline.fromTransaction(transaction, List.of(later, earlier));
        assertThat(timeline.points()).extracting(point -> point.occurredAt())
                .isSortedAccordingTo(Comparator.naturalOrder());

        ChargingTransaction noAnchors = completedTransaction(start, end, null, null, BigDecimal.ONE);
        assertFailure("METER_TIMELINE_MISSING", () -> MeterTimeline.fromTransaction(noAnchors, List.of()));
        assertFailure("METER_READING_NON_MONOTONIC", () -> MeterTimeline.fromTransaction(transaction,
                List.of(sample(transaction.id(), middle, "4.9"))));
        ChargingTransaction mismatch = completedTransaction(start, end,
                BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ONE);
        assertFailure("METER_ENERGY_MISMATCH", () -> MeterTimeline.fromTransaction(mismatch, List.of()));

        InMemorySamples samples = new InMemorySamples();
        ChargingMeterSample duplicate = sample(transaction.id(), middle, "5.5");
        assertThat(samples.saveIfAbsent(duplicate)).isTrue();
        assertThat(samples.saveIfAbsent(duplicate)).isFalse();
        assertThat(samples.findByTransactionId(transaction.id())).hasSize(1);
    }

    private static TariffPlan create(PricingType pricingType, List<TariffPeriod> periods) {
        return TariffPlan.create(UUID.randomUUID(), "Test", "CNY", pricingType, "Asia/Shanghai",
                periods, NOW, null, NOW);
    }

    private static TariffPlan fixedPlan(UUID tenantId, BigDecimal energy, BigDecimal service) {
        return fixedPlan(tenantId, energy, service, NOW.minusSeconds(60), null);
    }

    private static TariffPlan fixedPlan(UUID tenantId, BigDecimal energy, BigDecimal service,
                                        Instant from, Instant to) {
        return TariffPlan.create(tenantId, "Fixed", "CNY", PricingType.FIXED, "Asia/Shanghai",
                List.of(period(1, TariffPeriodType.FLAT, 0, 1440, energy.toPlainString(), service.toPlainString())),
                from, to, NOW);
    }

    private static TariffPlan timeOfUsePlan(UUID tenantId, Instant from, Instant to) {
        TariffPlan plan = TariffPlan.create(tenantId, "Standard TOU", "CNY", PricingType.TIME_OF_USE,
                "Asia/Shanghai", List.of(
                        period(1, TariffPeriodType.VALLEY, 0, 420, "0.60", "0.20"),
                        period(2, TariffPeriodType.PEAK, 420, 600, "1.20", "0.30"),
                        period(3, TariffPeriodType.FLAT, 600, 1020, "0.80", "0.20"),
                        period(4, TariffPeriodType.PEAK, 1020, 1260, "1.20", "0.30"),
                        period(5, TariffPeriodType.VALLEY, 1260, 1440, "0.60", "0.20")),
                from, to, NOW);
        plan.activate(NOW);
        return plan;
    }

    private static TariffPeriod period(int sequence, TariffPeriodType type, int from, int to,
                                       String energy, String service) {
        return new TariffPeriod(sequence, type, from, to, new BigDecimal(energy), new BigDecimal(service));
    }

    private static ChargingTransaction completedTransaction(Instant start, Instant end,
                                                             BigDecimal startMeter, BigDecimal endMeter,
                                                             BigDecimal energy) {
        UUID tenantId = UUID.randomUUID();
        ChargingTransaction transaction = ChargingTransaction.start(tenantId, UUID.randomUUID(), "TEST-DEVICE",
                UUID.randomUUID(), 1, DeviceProtocol.OCPP_16, UUID.randomUUID(), "TEST-ORDER", start.minusSeconds(1));
        transaction.begin("protocol-transaction", start, startMeter, ChargingStartMethod.REMOTE, start);
        transaction.finalizeRecord(start, end, startMeter, endMeter, energy, BigDecimal.ZERO, null,
                ChargingStartMethod.REMOTE, ChargingStopCategory.USER_STOP, 0, "test", null,
                end, "{}", "{}");
        return transaction;
    }

    private static Instant local(String value) {
        return LocalDateTime.parse(value).atZone(SHANGHAI).toInstant();
    }

    private static ChargingMeterSample sample(UUID transactionId, Instant at, String energy) {
        return new ChargingMeterSample(UUID.randomUUID(), transactionId, at, new BigDecimal(energy), null,
                null, DeviceProtocol.OCPP_16);
    }

    private static void assertFailure(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(VoltBridgeException.class)
                .extracting(error -> ((VoltBridgeException) error).code()).isEqualTo(code);
    }

    private static final class InMemoryTariffs implements TariffPlanRepository {
        private final Map<UUID, TariffPlan> values = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> revisionHighWaterMarks = new ConcurrentHashMap<>();
        @Override public void save(TariffPlan plan) {
            values.put(plan.id(), plan);
            revisionHighWaterMarks.merge(plan.familyId(), plan.revision(), Math::max);
        }
        @Override public Optional<TariffPlan> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<TariffPlan> findByTenantId(UUID tenantId) {
            return values.values().stream().filter(plan -> plan.tenantId().equals(tenantId)).toList();
        }
        @Override public List<TariffPlan> findEffectiveAt(UUID tenantId, TariffRole role, Instant at) {
            return findByTenantId(tenantId).stream().filter(plan -> plan.role() == role
                    && plan.effectiveAt(at)).toList();
        }
        @Override public List<TariffPlan> findActiveOverlapping(UUID tenantId, Instant from, Instant to,
                                                               UUID excludedId) {
            TariffEffectiveRange candidate = new TariffEffectiveRange(from, to);
            return findByTenantId(tenantId).stream().filter(plan -> !plan.id().equals(excludedId)
                    && plan.role() == TariffRole.SCHEDULED
                    && plan.status() == TariffPlanStatus.ACTIVE
                    && plan.effectiveRange().overlaps(candidate)).toList();
        }
        @Override public void lockTenantForTariffLifecycle(UUID tenantId) { }
        @Override public void lockFamilyForRevision(UUID familyId) { }
        @Override public int nextRevision(UUID familyId) {
            return revisionHighWaterMarks.merge(familyId, 1, Integer::sum);
        }
        @Override public boolean delete(UUID id) { return values.remove(id) != null; }
    }

    private static final class InMemorySamples implements com.voltbridge.business.domain.billing.ChargingMeterSampleRepository {
        private final Map<UUID, ChargingMeterSample> values = new ConcurrentHashMap<>();
        @Override public boolean saveIfAbsent(ChargingMeterSample sample) {
            return values.putIfAbsent(sample.eventId(), sample) == null;
        }
        @Override public List<ChargingMeterSample> findByTransactionId(UUID transactionId) {
            List<ChargingMeterSample> samples = new ArrayList<>(values.values().stream()
                    .filter(sample -> sample.transactionId().equals(transactionId)).toList());
            samples.sort(Comparator.comparing(ChargingMeterSample::occurredAt));
            return List.copyOf(samples);
        }
    }
}
