package com.voltbridge.business.web.billing;

import com.voltbridge.business.application.billing.BillCompletedTransactionUseCase;
import com.voltbridge.business.application.billing.GetBillingRecordUseCase;
import com.voltbridge.business.domain.billing.BillingLineItem;
import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 账单查询 API，仅展示已经不可变的计算结果与快照摘要。 */
@RestController
@RequestMapping("/api/billing-records")
public class BillingRecordController {
    private final GetBillingRecordUseCase get;
    private final BillCompletedTransactionUseCase billCompletedTransaction;

    public BillingRecordController(GetBillingRecordUseCase get,
                                   BillCompletedTransactionUseCase billCompletedTransaction) {
        this.get = get;
        this.billCompletedTransaction = billCompletedTransaction;
    }
    @GetMapping("/{billingRecordId}") public ApiResponse<BillingRecordView> find(@PathVariable UUID billingRecordId) { return ApiResponse.success(BillingRecordView.from(get.byId(billingRecordId))); }
    @GetMapping("/by-transaction/{transactionId}") public ApiResponse<BillingRecordView> findByTransaction(@PathVariable UUID transactionId) { return ApiResponse.success(BillingRecordView.from(get.byTransactionId(transactionId))); }
    /** 在资费补配或短暂基础设施故障修复后安全重试；已有账单直接原样返回。 */
    @PostMapping("/by-transaction/{transactionId}/retry") public ApiResponse<BillingRecordView> retry(@PathVariable UUID transactionId) { return ApiResponse.success(BillingRecordView.from(billCompletedTransaction.execute(transactionId))); }
    public record BillingRecordView(UUID billingRecordId, UUID transactionId, String status, String currency, BigDecimal energyKwh, BigDecimal energyFee, BigDecimal serviceFee, BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount, UUID tariffPlanId, String pricingSnapshot, List<LineItemView> lineItems, Instant chargingStartedAt, Instant chargingEndedAt, Instant finalizedAt) { static BillingRecordView from(BillingRecord bill) { return new BillingRecordView(bill.id(), bill.transactionId(), bill.status().name(), bill.currency(), bill.energyKwh(), bill.energyFee(), bill.serviceFee(), bill.subtotalAmount(), bill.discountAmount(), bill.totalAmount(), bill.tariffPlanId(), bill.pricingSnapshot(), bill.lineItems().stream().map(LineItemView::from).toList(), bill.chargingStartedAt(), bill.chargingEndedAt(), bill.finalizedAt()); } }
    public record LineItemView(String type, String description, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount) { static LineItemView from(BillingLineItem item) { return new LineItemView(item.type().name(), item.description(), item.quantity(), item.unit(), item.unitPrice(), item.amount()); } }
}
