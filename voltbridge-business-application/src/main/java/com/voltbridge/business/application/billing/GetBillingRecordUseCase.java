package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** 查询已生成账单；账单不会从资费方案重新计算。 */
@Service
public class GetBillingRecordUseCase {
    private final BillingRecordRepository bills;
    public GetBillingRecordUseCase(BillingRecordRepository bills) { this.bills = bills; }
    public BillingRecord byId(UUID billingRecordId) { return bills.findById(billingRecordId).orElseThrow(() -> new VoltBridgeException("BILLING_RECORD_NOT_FOUND", "Billing record not found: " + billingRecordId)); }
    public BillingRecord byTransactionId(UUID transactionId) { return bills.findByTransactionId(transactionId).orElseThrow(() -> new VoltBridgeException("BILLING_RECORD_NOT_FOUND", "Billing record not found for transaction: " + transactionId)); }
}
