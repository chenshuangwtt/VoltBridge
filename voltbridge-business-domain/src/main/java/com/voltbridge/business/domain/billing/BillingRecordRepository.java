package com.voltbridge.business.domain.billing;

import java.util.Optional;
import java.util.UUID;

/** 账单仓储端口；saveIfAbsent 的原子语义由 transaction_id 唯一约束最终保证。 */
public interface BillingRecordRepository {
    Optional<BillingRecord> findById(UUID id);
    Optional<BillingRecord> findByTransactionId(UUID transactionId);
    BillingRecord saveIfAbsent(BillingRecord billingRecord);
}
