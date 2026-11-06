package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.ChargingMeterSampleRepository;
import com.voltbridge.business.domain.billing.MeterTimeline;
import com.voltbridge.business.domain.billing.PricingContext;
import com.voltbridge.business.domain.billing.PricingEngine;
import com.voltbridge.business.domain.billing.PricingResult;
import com.voltbridge.business.domain.billing.PricingType;
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

/** 为已完成交易创建不可变账单及版本化计价证据。 */
@Service
public class BillCompletedTransactionUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillCompletedTransactionUseCase.class);

    private final ChargingTransactionRepository transactions;
    private final BillingRecordRepository bills;
    private final ChargingMeterSampleRepository meterSamples;
    private final TariffResolver tariffResolver;
    private final PricingEngine pricingEngine;
    private final PricingSnapshotSerializer snapshotSerializer;
    private final TimeProvider timeProvider;

    public BillCompletedTransactionUseCase(ChargingTransactionRepository transactions,
                                           BillingRecordRepository bills,
                                           ChargingMeterSampleRepository meterSamples,
                                           TariffResolver tariffResolver,
                                           PricingEngine pricingEngine,
                                           PricingSnapshotSerializer snapshotSerializer,
                                           TimeProvider timeProvider) {
        this.transactions = transactions;
        this.bills = bills;
        this.meterSamples = meterSamples;
        this.tariffResolver = tariffResolver;
        this.pricingEngine = pricingEngine;
        this.snapshotSerializer = snapshotSerializer;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public BillingRecord execute(UUID transactionId) {
        BillingRecord existing = bills.findByTransactionId(transactionId).orElse(null);
        if (existing != null) return existing;

        ChargingTransaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new VoltBridgeException("TRANSACTION_NOT_FOUND",
                        "Charging transaction not found: " + transactionId));
        if (transaction.status() != ChargingTransactionStatus.COMPLETED) {
            throw new VoltBridgeException("TRANSACTION_NOT_COMPLETED",
                    "Transaction is not completed: " + transactionId);
        }
        BigDecimal energy = transaction.energyKwh();
        if (energy == null) {
            throw new VoltBridgeException("BILLABLE_ENERGY_MISSING",
                    "Completed transaction has no final energy: " + transactionId);
        }

        TariffPlan tariff = tariffResolver.resolve(transaction);
        MeterTimeline timeline = tariff.pricingType() == PricingType.TIME_OF_USE
                ? MeterTimeline.fromTransaction(transaction, meterSamples.findByTransactionId(transaction.id()))
                : MeterTimeline.notRequired();
        PricingResult pricing = pricingEngine.calculate(new PricingContext(transaction, tariff, timeline));
        if (pricing.meterReconciliationDeltaKwh().signum() != 0) {
            LOGGER.warn("Meter energy reconciled within tolerance: transactionId={}, deltaKwh={}",
                    transaction.id(), pricing.meterReconciliationDeltaKwh());
        }
        String snapshot = snapshotSerializer.serialize(PricingSnapshot.from(tariff, pricing));
        BillingRecord candidate = BillingRecord.finalized(transaction.tenantId(), transaction.id(), tariff,
                transaction.startedAt(), transaction.endedAt(), pricing, snapshot, timeProvider.now());
        BillingRecord saved = bills.saveIfAbsent(candidate);
        LOGGER.info("Billing finalized: billingRecordId={}, transactionId={}, tariffPlanId={}, revision={}, energyKwh={}, totalAmount={}, currency={}",
                saved.id(), saved.transactionId(), saved.tariffPlanId(), tariff.revision(),
                saved.energyKwh(), saved.totalAmount(), saved.currency());
        return saved;
    }
}
