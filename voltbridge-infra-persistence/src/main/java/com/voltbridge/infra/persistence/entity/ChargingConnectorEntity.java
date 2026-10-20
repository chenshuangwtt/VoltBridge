package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@TableName("charging_connector")
public class ChargingConnectorEntity {

    @TableId
    public UUID id;
    public UUID tenantId;
    public UUID stationId;
    public UUID deviceId;
    public Integer connectorNo;
    public String connectorCode;
    public String connectorType;
    public BigDecimal ratedPower;
    public String status;
    public Instant createdAt;
    public Instant updatedAt;
    public Long version;
}
