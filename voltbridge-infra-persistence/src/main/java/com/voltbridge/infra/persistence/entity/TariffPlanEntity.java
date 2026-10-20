package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code tariff_plan} 表的持久化映射；方案费率不可通过业务 API 原地修改。 */
@TableName("tariff_plan")
public class TariffPlanEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID tenantId; public String name; public String currency; public String pricingType;
    public BigDecimal energyPricePerKwh; public BigDecimal serviceFeePerKwh;
    public Instant effectiveFrom; public Instant effectiveTo; public String status;
    public Instant activatedAt; public Instant createdAt;
    @Version public Long version;
}
