package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code tariff_plan} 表的持久化映射。 */
@TableName("tariff_plan")
public class TariffPlanEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID familyId; public Integer revision;
    public UUID tenantId; public String role; public String name; public String currency; public String pricingType;
    public String timezone;
    public Instant effectiveFrom; public Instant effectiveTo; public String status;
    public Instant activatedAt; public Instant retiredAt; public Instant createdAt;
    @Version public Long version;
}
