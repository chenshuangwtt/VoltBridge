package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.voltbridge.infra.persistence.typehandler.PostgreSqlJsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code billing_record} 表的不可变账单头映射。 */
@TableName(value = "billing_record", autoResultMap = true)
public class BillingRecordEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID tenantId; public UUID transactionId; public UUID tariffPlanId; public String currency;
    public BigDecimal energyKwh; public BigDecimal energyFee; public BigDecimal serviceFee;
    public BigDecimal subtotalAmount; public BigDecimal discountAmount; public BigDecimal totalAmount;
    public String status; public Instant chargingStartedAt; public Instant chargingEndedAt;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = PostgreSqlJsonbTypeHandler.class) public String pricingSnapshot;
    public Instant createdAt; public Instant finalizedAt;
}
