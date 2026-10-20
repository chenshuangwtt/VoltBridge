package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.FixedRatePricingPolicy;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceProtocol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

        PricingResult result = new FixedRatePricingPolicy().calculate(new BigDecimal("20.000000"), tariff);

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

        PricingResult zero = policy.calculate(BigDecimal.ZERO, tariff);
        PricingResult fractional = policy.calculate(new BigDecimal("1.234567"), tariff);

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
    void resolvesHistoricalEffectiveTariffAndExcludesExpiredAndFuturePlans() {
        UUID tenantId = UUID.randomUUID();
        InMemoryTariffs tariffs = new InMemoryTariffs();
        TariffPlan historical = activeTariff(tenantId, new BigDecimal("0.80"), new BigDecimal("0.20"),
                NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        TariffPlan current = activeTariff(tenantId, BigDecimal.ONE, BigDecimal.ZERO,
                NOW.minusSeconds(3600), null);
        TariffPlan future = activeTariff(tenantId, BigDecimal.TEN, BigDecimal.ZERO,
                NOW.plusSeconds(3600), null);
        tariffs.save(historical);
        tariffs.save(current);
        tariffs.save(future);
        UUID expiredTenantId = UUID.randomUUID();
        tariffs.save(activeTariff(expiredTenantId, BigDecimal.ONE, BigDecimal.ZERO,
                NOW.minusSeconds(7200), NOW.minusSeconds(3600)));

        assertThat(tariffs.findEffectiveActive(tenantId, NOW.minusSeconds(5400))).contains(historical);
        assertThat(tariffs.findEffectiveActive(tenantId, NOW)).contains(current);
        assertThat(tariffs.findEffectiveActive(tenantId, NOW.plusSeconds(7200))).contains(future);
        assertThat(tariffs.findEffectiveActive(expiredTenantId, NOW)).isEmpty();
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
        assertThatThrownBy(() -> new FixedRatePricingPolicy().calculate(new BigDecimal("-0.1"),
                activeTariff(tenantId, BigDecimal.ONE, BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bills.size()).isZero();
    }

    private BillCompletedTransactionUseCase billing(InMemoryTransactions transactions, InMemoryBills bills,
                                                    InMemoryTariffs tariffs) {
        return new BillCompletedTransactionUseCase(transactions, bills,
                new DefaultTariffResolver(tariffs), new DefaultPricingEngine(), clock());
    }

    private TimeProvider clock() {
        return () -> NOW;
    }

    private TariffPlan activeTariff(UUID tenantId, BigDecimal energyPrice, BigDecimal serviceFee) {
        return activeTariff(tenantId, energyPrice, serviceFee, NOW.minusSeconds(7200), null);
    }

    private TariffPlan activeTariff(UUID tenantId, BigDecimal energyPrice, BigDecimal serviceFee,
                                    Instant effectiveFrom, Instant effectiveTo) {
        TariffPlan tariff = TariffPlan.create(tenantId, "MVP", "CNY", energyPrice, serviceFee,
                effectiveFrom, effectiveTo, NOW);
        tariff.activate(NOW);
        return tariff;
    }

    private ChargingTransaction completedTransaction(UUID tenantId, DeviceProtocol protocol, BigDecimal energy) {
        ChargingTransaction transaction = ChargingTransaction.start(tenantId, UUID.randomUUID(), "DEVICE-001",
                UUID.randomUUID(), 1, protocol, UUID.randomUUID(), "test", NOW.minusSeconds(3600));
        transaction.begin("1001", NOW.minusSeconds(3500), BigDecimal.ZERO,
                ChargingStartMethod.REMOTE, NOW.minusSeconds(3500));
        transaction.finalizeRecord(NOW.minusSeconds(3500), NOW.minusSeconds(10), BigDecimal.ZERO, energy, energy,
                new BigDecimal("999"), null, ChargingStartMethod.REMOTE, ChargingStopCategory.USER_STOP,
                0, "done", null, NOW, "{}", "{}");
        return transaction;
    }

    private static final class InMemoryTariffs implements TariffPlanRepository {
        private final Map<UUID, TariffPlan> values = new ConcurrentHashMap<>();

        @Override
        public void save(TariffPlan plan) {
            values.put(plan.id(), plan);
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
        public Optional<TariffPlan> findEffectiveActive(UUID tenantId, Instant time) {
            return values.values().stream()
                    .filter(plan -> plan.tenantId().equals(tenantId) && plan.effectiveAt(time))
                    .max(Comparator.comparing(TariffPlan::effectiveFrom));
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
