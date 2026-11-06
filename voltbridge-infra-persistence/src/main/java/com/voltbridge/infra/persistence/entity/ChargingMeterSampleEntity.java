package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code charging_meter_sample} 表的计费相关累计电表读数。 */
@TableName("charging_meter_sample")
public class ChargingMeterSampleEntity {
    @TableId(type = IdType.INPUT) public UUID eventId;
    public UUID transactionId;
    public Instant occurredAt;
    public BigDecimal energyKwh;
    public BigDecimal powerKw;
    public Integer stateOfCharge;
    public String protocol;
    public Instant createdAt;
}
