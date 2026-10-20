package com.voltbridge.business.application.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 在完成交易提交后执行计费，避免资费缺失等问题回滚已确认的物理充电交易。 */
@Component
public class BillingTransactionCompletionListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillingTransactionCompletionListener.class);
    private final BillCompletedTransactionUseCase billing;
    public BillingTransactionCompletionListener(BillCompletedTransactionUseCase billing) { this.billing = billing; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(ChargingTransactionCompletedEvent event) {
        try { billing.execute(event.transactionId()); }
        catch (RuntimeException exception) { LOGGER.error("Billing deferred after completed transaction: transactionId={}, reason={}", event.transactionId(), exception.getMessage(), exception); }
    }
}
