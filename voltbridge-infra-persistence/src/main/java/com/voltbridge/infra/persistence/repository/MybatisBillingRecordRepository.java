package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.voltbridge.business.domain.billing.BillingLineItem;
import com.voltbridge.business.domain.billing.BillingLineItemType;
import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.BillingStatus;
import com.voltbridge.infra.persistence.entity.BillingLineItemEntity;
import com.voltbridge.infra.persistence.entity.BillingRecordEntity;
import com.voltbridge.infra.persistence.mapper.BillingLineItemMapper;
import com.voltbridge.infra.persistence.mapper.BillingRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 账单仓储用 transaction_id 唯一索引抵御多节点并发重复计费。 */
@Repository
public class MybatisBillingRecordRepository implements BillingRecordRepository {
    private final BillingRecordMapper records;
    private final BillingLineItemMapper lines;

    public MybatisBillingRecordRepository(BillingRecordMapper records, BillingLineItemMapper lines) {
        this.records = records;
        this.lines = lines;
    }

    @Override public Optional<BillingRecord> findById(UUID id) { return Optional.ofNullable(records.selectById(id)).map(this::domain); }
    @Override public Optional<BillingRecord> findByTransactionId(UUID transactionId) {
        return Optional.ofNullable(records.selectOne(new QueryWrapper<BillingRecordEntity>().eq("transaction_id", transactionId))).map(this::domain);
    }

    @Override
    @Transactional
    public BillingRecord saveIfAbsent(BillingRecord bill) {
        BillingRecord existing = findByTransactionId(bill.transactionId()).orElse(null);
        if (existing != null) return existing;
        try {
            records.insert(entity(bill));
        } catch (DuplicateKeyException duplicate) {
            return findByTransactionId(bill.transactionId()).orElseThrow(() -> duplicate);
        }
        for (int index = 0; index < bill.lineItems().size(); index++) {
            BillingLineItem item = bill.lineItems().get(index);
            BillingLineItemEntity entity = new BillingLineItemEntity();
            entity.billingRecordId = bill.id(); entity.lineNo = index + 1; entity.lineType = item.type().name();
            entity.description = item.description(); entity.quantity = item.quantity(); entity.unit = item.unit();
            entity.unitPrice = item.unitPrice(); entity.amount = item.amount();
            lines.insert(entity);
        }
        return bill;
    }

    private BillingRecordEntity entity(BillingRecord bill) {
        BillingRecordEntity value = new BillingRecordEntity();
        value.id = bill.id(); value.tenantId = bill.tenantId(); value.transactionId = bill.transactionId(); value.tariffPlanId = bill.tariffPlanId(); value.currency = bill.currency();
        value.energyKwh = bill.energyKwh(); value.energyFee = bill.energyFee(); value.serviceFee = bill.serviceFee();
        value.subtotalAmount = bill.subtotalAmount(); value.discountAmount = bill.discountAmount(); value.totalAmount = bill.totalAmount();
        value.status = bill.status().name(); value.chargingStartedAt = bill.chargingStartedAt(); value.chargingEndedAt = bill.chargingEndedAt();
        value.pricingSnapshot = bill.pricingSnapshot(); value.createdAt = bill.createdAt(); value.finalizedAt = bill.finalizedAt();
        return value;
    }

    private BillingRecord domain(BillingRecordEntity value) {
        List<BillingLineItem> items = lines.selectList(new QueryWrapper<BillingLineItemEntity>()
                        .eq("billing_record_id", value.id).orderByAsc("line_no")).stream()
                .map(item -> new BillingLineItem(BillingLineItemType.valueOf(item.lineType), item.description,
                        item.quantity, item.unit, item.unitPrice, item.amount)).toList();
        return new BillingRecord(value.id, value.tenantId, value.transactionId, value.tariffPlanId, value.currency,
                value.energyKwh, value.energyFee, value.serviceFee, value.subtotalAmount, value.discountAmount,
                value.totalAmount, BillingStatus.valueOf(value.status), value.chargingStartedAt, value.chargingEndedAt,
                value.pricingSnapshot, items, value.createdAt, value.finalizedAt);
    }
}
