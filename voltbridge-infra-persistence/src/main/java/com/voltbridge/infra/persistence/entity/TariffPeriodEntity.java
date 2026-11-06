package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code tariff_period} 表的一行日内价格定义。 */
@TableName("tariff_period")
public class TariffPeriodEntity {
    public UUID tariffPlanId;
    public Integer sequenceNo;
    public String periodType;
    public Integer startMinute;
    public Integer endMinute;
    public BigDecimal energyPricePerKwh;
    public BigDecimal serviceFeePerKwh;
}
