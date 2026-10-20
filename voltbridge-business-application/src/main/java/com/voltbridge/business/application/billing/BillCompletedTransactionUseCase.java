package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.PricingEngine;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffResolver;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 为已完成交易创建不可变账单。
 *
 * <p>先查询现有账单、再解析资费、最后由带唯一约束的仓储原子保存；不使用 JVM 全局锁，
 * 因而可在 Kafka 至少一次投递和多 Business 实例下安全重试。</p>
 */
@Service
public class BillCompletedTransactionUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillCompletedTransactionUseCase.class);
    private final ChargingTransactionRepository transactions; private final BillingRecordRepository bills;
    private final TariffResolver tariffResolver; private final PricingEngine pricingEngine; private final TimeProvider timeProvider;
    public BillCompletedTransactionUseCase(ChargingTransactionRepository transactions, BillingRecordRepository bills,
                                           TariffResolver tariffResolver, PricingEngine pricingEngine, TimeProvider timeProvider) {
        this.transactions = transactions; this.bills = bills; this.tariffResolver = tariffResolver;
        this.pricingEngine = pricingEngine; this.timeProvider = timeProvider;
    }
    @Transactional
    public BillingRecord execute(UUID transactionId) {
        BillingRecord existing = bills.findByTransactionId(transactionId).orElse(null);
        if (existing != null) return existing;
        ChargingTransaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new VoltBridgeException("TRANSACTION_NOT_FOUND", "Charging transaction not found: " + transactionId));
        if (transaction.status() != ChargingTransactionStatus.COMPLETED) throw new VoltBridgeException("TRANSACTION_NOT_COMPLETED", "Transaction is not completed: " + transactionId);
        BigDecimal energy = transaction.energyKwh();
        if (energy == null) throw new VoltBridgeException("BILLABLE_ENERGY_MISSING", "Completed transaction has no final energy: " + transactionId);
        TariffPlan tariff = tariffResolver.resolve(transaction);
        PricingResult pricing = pricingEngine.calculate(energy, tariff);
        BillingRecord candidate = BillingRecord.finalized(transaction.tenantId(), transaction.id(), tariff,
                transaction.startedAt(), transaction.endedAt(), pricing, snapshot(tariff), timeProvider.now());
        BillingRecord saved = bills.saveIfAbsent(candidate);
        LOGGER.info("Billing finalized: billingRecordId={}, transactionId={}, tariffPlanId={}, energyKwh={}, totalAmount={}, currency={}",
                saved.id(), saved.transactionId(), saved.tariffPlanId(), saved.energyKwh(), saved.totalAmount(), saved.currency());
        return saved;
    }
    private String snapshot(TariffPlan tariff) {
        return "{\"pricingType\":\"" + tariff.pricingType() + "\",\"tariffPlanId\":\"" + tariff.id()
                + "\",\"tariffVersion\":" + tariff.version() + ",\"tariffName\":\"" + escape(tariff.name())
                + "\",\"currency\":\"" + escape(tariff.currency()) + "\",\"energyPricePerKwh\":" + tariff.energyPricePerKwh()
                + ",\"serviceFeePerKwh\":" + tariff.serviceFeePerKwh() + ",\"effectiveFrom\":\"" + tariff.effectiveFrom()
                + "\",\"effectiveTo\":" + (tariff.effectiveTo() == null ? "null" : "\"" + tariff.effectiveTo() + "\"") + "}";
    }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
