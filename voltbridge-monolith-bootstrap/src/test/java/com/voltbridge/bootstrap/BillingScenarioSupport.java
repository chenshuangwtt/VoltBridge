package com.voltbridge.bootstrap;

import com.voltbridge.business.application.billing.BillCompletedTransactionUseCase;
import com.voltbridge.business.application.billing.ChargingTransactionCompletedEvent;
import com.voltbridge.business.application.billing.DefaultPricingEngine;
import com.voltbridge.business.application.billing.DefaultTariffResolver;
import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.common.time.TimeProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 为协议模拟器夹具接入真实 Billing 应用用例的最小内存适配器。
 *
 * <p>模拟器仍只发送设备报文；此类仅模拟 Business 内部完成事件已经在提交后被消费，
 * 用于验证两种协议归一化为完成交易后确实走同一套资费和账单逻辑。</p>
 */
final class BillingScenarioSupport {
    private final BillCompletedTransactionUseCase billing;
    private final InMemoryBillingRecords records = new InMemoryBillingRecords();

    BillingScenarioSupport(ChargingTransactionRepository transactions, UUID tenantId) {
        InMemoryTariffs tariffs = new InMemoryTariffs();
        TariffPlan tariff = TariffPlan.create(tenantId, "Simulator demo tariff", "CNY",
                new BigDecimal("0.80"), new BigDecimal("0.20"), Instant.EPOCH, null, Instant.now());
        tariff.activate(Instant.now());
        tariffs.save(tariff);
        TimeProvider clock = Instant::now;
        this.billing = new BillCompletedTransactionUseCase(transactions, records,
                new DefaultTariffResolver(tariffs), new DefaultPricingEngine(), clock);
    }

    void onBusinessEvent(Object event) {
        if (event instanceof ChargingTransactionCompletedEvent completed) {
            billing.execute(completed.transactionId());
        }
    }

    BillingRecord requireByTransactionId(UUID transactionId) {
        return records.findByTransactionId(transactionId).orElseThrow();
    }

    private static final class InMemoryTariffs implements TariffPlanRepository {
        private final ConcurrentMap<UUID, TariffPlan> values = new ConcurrentHashMap<>();

        @Override
        public void save(TariffPlan plan) {
            values.put(plan.id(), plan);
        }

        @Override
        public Optional<TariffPlan> findById(UUID tariffPlanId) {
            return Optional.ofNullable(values.get(tariffPlanId));
        }

        @Override
        public List<TariffPlan> findByTenantId(UUID tenantId) {
            return values.values().stream().filter(plan -> plan.tenantId().equals(tenantId)).toList();
        }

        @Override
        public Optional<TariffPlan> findEffectiveActive(UUID tenantId, Instant effectiveAt) {
            return findByTenantId(tenantId).stream().filter(plan -> plan.effectiveAt(effectiveAt))
                    .max(Comparator.comparing(TariffPlan::effectiveFrom));
        }
    }

    private static final class InMemoryBillingRecords implements BillingRecordRepository {
        private final ConcurrentMap<UUID, BillingRecord> values = new ConcurrentHashMap<>();

        @Override
        public Optional<BillingRecord> findById(UUID billingRecordId) {
            return values.values().stream().filter(record -> record.id().equals(billingRecordId)).findFirst();
        }

        @Override
        public Optional<BillingRecord> findByTransactionId(UUID transactionId) {
            return Optional.ofNullable(values.get(transactionId));
        }

        @Override
        public BillingRecord saveIfAbsent(BillingRecord record) {
            return values.putIfAbsent(record.transactionId(), record) == null
                    ? record : values.get(record.transactionId());
        }
    }
}
