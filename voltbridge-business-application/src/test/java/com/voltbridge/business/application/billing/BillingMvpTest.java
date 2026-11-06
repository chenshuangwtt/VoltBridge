package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.ChargingMeterSample;
import com.voltbridge.business.domain.billing.ChargingMeterSampleRepository;
import com.voltbridge.business.domain.billing.MeterTimeline;
import com.voltbridge.business.domain.billing.PricingContext;
import com.voltbridge.business.domain.billing.FixedRatePricingPolicy;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.business.domain.billing.TariffEffectiveRange;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计费 MVP 的业务回归测试。
 *
 * <p>测试直接构造规范交易而非协议报文，以证明计费只依赖完成后的
 * {@link ChargingTransaction}，对 YunKuaiChong 和 OCPP 保持一致。</p>
 */
class BillingMvpTest {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void calculatesFixedRateWithCentralizedRounding() {
        TariffPlan tariff = activeTariff(UUID.randomUUID(), new BigDecimal("0.80"), new BigDecimal("0.20"));

        ChargingTransaction transaction = completedTransaction(tariff.tenantId(), DeviceProtocol.OCPP_16,
                new BigDecimal("20.000000"));
        PricingResult result = new FixedRatePricingPolicy().calculate(
                new PricingContext(transaction, tariff, MeterTimeline.notRequired()));

        assertThat(result.energyFee()).isEqualByComparingTo("16.00");
        assertThat(result.serviceFee()).isEqualByComparingTo("4.00");
        assertThat(result.subtotalAmount()).isEqualByComparingTo("20.00");
        assertThat(result.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(result.lineItems()).hasSize(2);
    }

    @Test
    void supportsZeroAndFractionalEnergyWithExplicitMoneyRounding() {
        TariffPlan tariff = activeTariff(UUID.randomUUID(), new BigDecimal("0.123456"), BigDecimal.ZERO);
        FixedRatePricingPolicy policy = new FixedRatePricingPolicy();

        PricingResult zero = policy.calculate(new PricingContext(
                completedTransaction(tariff.tenantId(), DeviceProtocol.OCPP_16, BigDecimal.ZERO), tariff,
                MeterTimeline.notRequired()));
        PricingResult fractional = policy.calculate(new PricingContext(
                completedTransaction(tariff.tenantId(), DeviceProtocol.OCPP_16, new BigDecimal("1.234567")), tariff,
                MeterTimeline.notRequired()));

        assertThat(zero.totalAmount()).isEqualByComparingTo("0.00");
        assertThat(fractional.energyFee()).isEqualByComparingTo("0.15");
        assertThat(fractional.serviceFee()).isEqualByComparingTo("0.00");
        assertThat(fractional.totalAmount()).isEqualByComparingTo("0.15");
    }

    @Test
    void resolvesTariffAtTransactionStartAndCreatesOnlyOneBillConcurrently() throws Exception {
        UUID tenantId = UUID.randomUUID();
        InMemoryTransactions transactions = new InMemoryTransactions();
        ChargingTransaction transaction = completedTransaction(tenantId, DeviceProtocol.OCPP_16, new BigDecimal("20.000000"));
        transactions.save(transaction);
        InMemoryTariffs tariffs = new InMemoryTariffs();
        tariffs.save(activeTariff(tenantId, new BigDecimal("0.80"), new BigDecimal("0.20")));
        InMemoryBills bills = new InMemoryBills();
        BillCompletedTransactionUseCase billing = billing(transactions, bills, tariffs);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<BillingRecord>> futures = executor.invokeAll(List.of(
                    () -> billing.execute(transaction.id()),
                    () -> billing.execute(transaction.id()),
                    () -> billing.execute(transaction.id())));
            List<BillingRecord> records = new ArrayList<>();
            for (Future<BillingRecord> future : futures) {
                records.add(future.get());
            }

            assertThat(records).extracting(BillingRecord::id).containsOnly(records.getFirst().id());
            assertThat(bills.size()).isOne();
            assertThat(records.getFirst().totalAmount()).isEqualByComparingTo("20.00");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void billsYunKuaiChongAndOcppThroughTheSameProtocolNeutralUseCase() {
        UUID tenantId = UUID.randomUUID();
        InMemoryTransactions transactions = new InMemoryTransactions();
        ChargingTransaction yunKuaiChong = completedTransaction(tenantId, DeviceProtocol.YUN_KUAI_CHONG_150, new BigDecimal("20"));
        ChargingTransaction ocpp = completedTransaction(tenantId, DeviceProtocol.OCPP_16, new BigDecimal("20"));
        transactions.save(yunKuaiChong);
        transactions.save(ocpp);
        InMemoryTariffs tariffs = new InMemoryTariffs();
        tariffs.save(activeTariff(tenantId, new BigDecimal("0.80"), new BigDecimal("0.20")));
        InMemoryBills bills = new InMemoryBills();
        BillCompletedTransactionUseCase billing = billing(transactions, bills, tariffs);

        BillingRecord ykcBill = billing.execute(yunKuaiChong.id());
        BillingRecord ocppBill = billing.execute(ocpp.id());

        assertThat(ykcBill.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(ocppBill.totalAmount()).isEqualByComparingTo("20.00");
        assertThat(ykcBill.pricingSnapshot()).contains("FIXED", "0.80", "0.20");
        assertThat(ocppBill.pricingSnapshot()).contains("FIXED", "0.80", "0.20");
        assertThat(bills.size()).isEqualTo(2);
    }

    @Test
    void completesTimeOfUseBillingAndKeepsHistoricalSnapshotAfterNextRevision() {
        UUID tenantId = UUID.randomUUID();
        Instant start = LocalDateTime.parse("2026-11-01T06:50:00")
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant boundary = LocalDateTime.parse("2026-11-01T07:00:00")
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant end = LocalDateTime.parse("2026-11-01T07:10:00")
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        ChargingTransaction transaction = completedTransaction(tenantId, DeviceProtocol.OCPP_16,
                start, end, new BigDecimal("100.000000"), new BigDecimal("100.060000"), new BigDecimal("0.060000"));
        InMemoryTransactions transactions = new InMemoryTransactions();
        transactions.save(transaction);
        InMemoryTariffs tariffs = new InMemoryTariffs();
        TariffPlan tariff = TariffPlan.create(tenantId, "Daily TOU", "CNY", PricingType.TIME_OF_USE,
                "Asia/Shanghai", List.of(
                        new TariffPeriod(1, com.voltbridge.business.domain.billing.TariffPeriodType.VALLEY,
                                0, 420, new BigDecimal("0.60"), new BigDecimal("0.20")),
                        new TariffPeriod(2, com.voltbridge.business.domain.billing.TariffPeriodType.PEAK,
                                420, 600, new BigDecimal("1.20"), new BigDecimal("0.30")),
                        new TariffPeriod(3, com.voltbridge.business.domain.billing.TariffPeriodType.FLAT,
                                600, 1020, new BigDecimal("0.80"), new BigDecimal("0.20")),
                        new TariffPeriod(4, com.voltbridge.business.domain.billing.TariffPeriodType.PEAK,
                                1020, 1260, new BigDecimal("1.20"), new BigDecimal("0.30")),
                        new TariffPeriod(5, com.voltbridge.business.domain.billing.TariffPeriodType.VALLEY,
                                1260, 1440, new BigDecimal("0.60"), new BigDecimal("0.20"))),
                Instant.EPOCH, null, NOW);
        tariff.activate(NOW);
        tariffs.save(tariff);
        InMemoryBills bills = new InMemoryBills();
        InMemoryMeterSamples samples = new InMemoryMeterSamples();
        samples.saveIfAbsent(new ChargingMeterSample(UUID.randomUUID(), transaction.id(), boundary,
                new BigDecimal("100.030000"), new BigDecimal("7.2"), 45, DeviceProtocol.OCPP_16));
        BillCompletedTransactionUseCase billing = new BillCompletedTransactionUseCase(transactions, bills, samples,
                new DefaultTariffResolver(tariffs), new DefaultPricingEngine(),
                new PricingSnapshotSerializer(new ObjectMapper().findAndRegisterModules()), clock());

        BillingRecord bill = billing.execute(transaction.id());
        String historicalSnapshot = bill.pricingSnapshot();
        assertThat(bill.tariffPlanId()).isEqualTo(tariff.id());
        assertThat(bill.energyFee()).isEqualByComparingTo("0.06");
        assertThat(bill.serviceFee()).isEqualByComparingTo("0.02");
        assertThat(bill.totalAmount()).isEqualByComparingTo("0.08");
        assertThat(bill.lineItems()).hasSize(4);
        assertThat(historicalSnapshot).contains("familyId", "revision", "TIME_OF_USE", "Asia/Shanghai",
                "appliedSegments", "VALLEY", "PEAK");

        TariffPlan revision = new CreateTariffRevisionUseCase(tariffs, clock()).execute(tariff.id(),
                "Daily TOU revised", "CNY", PricingType.TIME_OF_USE, "Asia/Shanghai", tariff.periods(),
                end, null);
        assertThat(revision.revision()).isEqualTo(2);
        BillingRecord repeated = billing.execute(transaction.id());
        assertThat(repeated.pricingSnapshot()).isEqualTo(historicalSnapshot);
        assertThat(repeated.totalAmount()).isEqualByComparingTo("0.08");
    }

    @Test
    void resolvesHistoricalEffectiveTariffAndExcludesExpiredAndFuturePlans() {
        UUID tenantId = UUID.randomUUID();
        InMemoryTariffs tariffs = new InMemoryTariffs();
        TariffPlan historical = activeTariff(tenantId, new BigDecimal("0.80"), new BigDecimal("0.20"),
                NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        TariffPlan current = activeTariff(tenantId, BigDecimal.ONE, BigDecimal.ZERO,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        TariffPlan future = activeTariff(tenantId, BigDecimal.TEN, BigDecimal.ZERO,
                NOW.plusSeconds(3600), null);
        tariffs.save(historical);
        tariffs.save(current);
        tariffs.save(future);
        UUID expiredTenantId = UUID.randomUUID();
        tariffs.save(activeTariff(expiredTenantId, BigDecimal.ONE, BigDecimal.ZERO,
                NOW.minusSeconds(7200), NOW.minusSeconds(3600)));

        assertThat(tariffs.findEffectiveAt(tenantId, TariffRole.SCHEDULED, NOW.minusSeconds(5400))).containsExactly(historical);
        assertThat(tariffs.findEffectiveAt(tenantId, TariffRole.SCHEDULED, NOW)).containsExactly(current);
        assertThat(tariffs.findEffectiveAt(tenantId, TariffRole.SCHEDULED, NOW.plusSeconds(7200))).containsExactly(future);
        assertThat(tariffs.findEffectiveAt(expiredTenantId, TariffRole.SCHEDULED, NOW)).isEmpty();
    }

    @Test
    void rejectsIncompleteAndNegativeEnergyWithoutCreatingZeroBill() {
        UUID tenantId = UUID.randomUUID();
        InMemoryTransactions transactions = new InMemoryTransactions();
        ChargingTransaction transaction = ChargingTransaction.start(tenantId, UUID.randomUUID(), "DEVICE-001",
                UUID.randomUUID(), 1, DeviceProtocol.OCPP_16, UUID.randomUUID(), "test", NOW);
        transactions.save(transaction);
        InMemoryTariffs tariffs = new InMemoryTariffs();
        tariffs.save(activeTariff(tenantId, BigDecimal.ONE, BigDecimal.ZERO));
        InMemoryBills bills = new InMemoryBills();
        BillCompletedTransactionUseCase billing = billing(transactions, bills, tariffs);

        assertThatThrownBy(() -> billing.execute(transaction.id()))
                .isInstanceOf(VoltBridgeException.class)
                .hasMessageContaining("not completed");
        assertThatThrownBy(() -> new FixedRatePricingPolicy().calculate(new PricingContext(
                completedTransaction(tenantId, DeviceProtocol.OCPP_16, new BigDecimal("-0.1")),
                activeTariff(tenantId, BigDecimal.ONE, BigDecimal.ZERO), MeterTimeline.notRequired())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bills.size()).isZero();
    }

    private BillCompletedTransactionUseCase billing(InMemoryTransactions transactions, InMemoryBills bills,
                                                    InMemoryTariffs tariffs) {
        return new BillCompletedTransactionUseCase(transactions, bills,
                new InMemoryMeterSamples(), new DefaultTariffResolver(tariffs), new DefaultPricingEngine(),
                new PricingSnapshotSerializer(new ObjectMapper().findAndRegisterModules()), clock());
    }

    private TimeProvider clock() {
        return () -> NOW;
    }

    private TariffPlan activeTariff(UUID tenantId, BigDecimal energyPrice, BigDecimal serviceFee) {
        return activeTariff(tenantId, energyPrice, serviceFee, NOW.minusSeconds(7200), null);
    }

    private TariffPlan activeTariff(UUID tenantId, BigDecimal energyPrice, BigDecimal serviceFee,
                                    Instant effectiveFrom, Instant effectiveTo) {
        TariffPlan tariff = TariffPlan.create(tenantId, "MVP", "CNY", PricingType.FIXED,
                "Asia/Shanghai", List.of(new TariffPeriod(1, TariffPeriodType.FLAT, 0, 1440,
                        energyPrice, serviceFee)), effectiveFrom, effectiveTo, NOW);
        tariff.activate(NOW);
        return tariff;
    }

    private ChargingTransaction completedTransaction(UUID tenantId, DeviceProtocol protocol, BigDecimal energy) {
        return completedTransaction(tenantId, protocol, NOW.minusSeconds(3500), NOW.minusSeconds(10),
                BigDecimal.ZERO, energy, energy);
    }

    private ChargingTransaction completedTransaction(UUID tenantId, DeviceProtocol protocol,
                                                     Instant startedAt, Instant endedAt,
                                                     BigDecimal startMeter, BigDecimal endMeter,
                                                     BigDecimal energy) {
        ChargingTransaction transaction = ChargingTransaction.start(tenantId, UUID.randomUUID(), "DEVICE-001",
                UUID.randomUUID(), 1, protocol, UUID.randomUUID(), "test", startedAt.minusSeconds(60));
        transaction.begin("1001", startedAt, startMeter, ChargingStartMethod.REMOTE, startedAt);
        transaction.finalizeRecord(startedAt, endedAt, startMeter, endMeter, energy,
                new BigDecimal("999"), null, ChargingStartMethod.REMOTE, ChargingStopCategory.USER_STOP,
                0, "done", null, endedAt, "{}", "{}");
        return transaction;
    }

    private static final class InMemoryTariffs implements TariffPlanRepository {
        private final Map<UUID, TariffPlan> values = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> revisionHighWaterMarks = new ConcurrentHashMap<>();

        @Override
        public void save(TariffPlan plan) {
            values.put(plan.id(), plan);
            revisionHighWaterMarks.merge(plan.familyId(), plan.revision(), Math::max);
        }

        @Override
        public Optional<TariffPlan> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public List<TariffPlan> findByTenantId(UUID tenantId) {
            return values.values().stream().filter(plan -> plan.tenantId().equals(tenantId)).toList();
        }

        @Override
        public List<TariffPlan> findEffectiveAt(UUID tenantId, TariffRole role, Instant time) {
            List<TariffPlan> matches = values.values().stream()
                    .filter(plan -> plan.tenantId().equals(tenantId)
                            && plan.role() == role && plan.effectiveAt(time))
                    .toList();
            return matches;
        }

        @Override
        public List<TariffPlan> findActiveOverlapping(UUID tenantId, Instant from, Instant to,
                                                      UUID excludingPlanId) {
            TariffEffectiveRange candidate = new TariffEffectiveRange(from, to);
            return values.values().stream().filter(plan -> !plan.id().equals(excludingPlanId)
                    && plan.tenantId().equals(tenantId) && plan.role() == TariffRole.SCHEDULED
                    && plan.status() == com.voltbridge.business.domain.billing.TariffPlanStatus.ACTIVE
                    && plan.effectiveRange().overlaps(candidate)).toList();
        }

        @Override public void lockTenantForTariffLifecycle(UUID tenantId) { }
        @Override public void lockFamilyForRevision(UUID familyId) { }

        @Override
        public int nextRevision(UUID familyId) {
            return revisionHighWaterMarks.merge(familyId, 1, Integer::sum);
        }
        @Override public boolean delete(UUID id) { return values.remove(id) != null; }
    }

    private static final class InMemoryMeterSamples implements ChargingMeterSampleRepository {
        private final ConcurrentMap<UUID, ChargingMeterSample> values = new ConcurrentHashMap<>();
        @Override public boolean saveIfAbsent(ChargingMeterSample sample) {
            return values.putIfAbsent(sample.eventId(), sample) == null;
        }
        @Override public List<ChargingMeterSample> findByTransactionId(UUID transactionId) {
            return values.values().stream().filter(sample -> sample.transactionId().equals(transactionId))
                    .sorted(java.util.Comparator.comparing(ChargingMeterSample::occurredAt)).toList();
        }
    }

    private static final class InMemoryBills implements BillingRecordRepository {
        private final ConcurrentMap<UUID, BillingRecord> values = new ConcurrentHashMap<>();

        @Override
        public Optional<BillingRecord> findById(UUID id) {
            return values.values().stream().filter(value -> value.id().equals(id)).findFirst();
        }

        @Override
        public Optional<BillingRecord> findByTransactionId(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public boolean existsByTariffPlanId(UUID tariffPlanId) {
            return values.values().stream().anyMatch(value -> value.tariffPlanId().equals(tariffPlanId));
        }

        @Override
        public BillingRecord saveIfAbsent(BillingRecord bill) {
            return values.putIfAbsent(bill.transactionId(), bill) == null ? bill : values.get(bill.transactionId());
        }

        int size() {
            return values.size();
        }
    }

    private static final class InMemoryTransactions implements ChargingTransactionRepository {
        private final Map<UUID, ChargingTransaction> values = new ConcurrentHashMap<>();

        @Override
        public void save(ChargingTransaction transaction) {
            values.put(transaction.id(), transaction);
        }

        @Override
        public Optional<ChargingTransaction> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public Optional<ChargingTransaction> findByCommandId(UUID id) {
            return values.values().stream().filter(value -> Objects.equals(value.commandId(), id)).findFirst();
        }

        @Override
        public Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol, UUID deviceId,
                                                                          String transactionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId) {
            return Optional.empty();
        }

        @Override
        public List<ChargingTransaction> find(String deviceCode, Integer connectorNo, ChargingTransactionStatus status) {
            return List.of();
        }
    }
}
