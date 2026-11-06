package com.voltbridge.infra.persistence.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.BillingRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
/** 账单头基础 Mapper。 */
@Mapper public interface BillingRecordMapper extends BaseMapper<BillingRecordEntity> {
    @Insert("""
            INSERT INTO billing_record (id, tenant_id, transaction_id, tariff_plan_id, currency,
                energy_kwh, energy_fee, service_fee, subtotal_amount, discount_amount, total_amount,
                status, charging_started_at, charging_ended_at, pricing_snapshot, created_at, finalized_at)
            VALUES (#{record.id}, #{record.tenantId}, #{record.transactionId}, #{record.tariffPlanId},
                #{record.currency}, #{record.energyKwh}, #{record.energyFee}, #{record.serviceFee},
                #{record.subtotalAmount}, #{record.discountAmount}, #{record.totalAmount}, #{record.status},
                #{record.chargingStartedAt}, #{record.chargingEndedAt},
                #{record.pricingSnapshot,jdbcType=OTHER,typeHandler=com.voltbridge.infra.persistence.typehandler.PostgreSqlJsonbTypeHandler},
                #{record.createdAt}, #{record.finalizedAt})
            ON CONFLICT (transaction_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("record") BillingRecordEntity record);
}
