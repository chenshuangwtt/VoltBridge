package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.voltbridge.infra.persistence.typehandler.PostgreSqlJsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "charging_device", autoResultMap = true)
public class ChargingDeviceEntity {

    @TableId
    public UUID id;
    public UUID tenantId;
    public UUID stationId;
    public String deviceCode;
    public String deviceName;
    public String protocol;
    public String protocolVersion;
    public String manufacturer;
    public String brand;
    public String model;
    public String deviceType;
    public String status;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = PostgreSqlJsonbTypeHandler.class)
    public String additionalInfo;
    public Instant createdAt;
    public Instant updatedAt;
    @Version
    public Long version;
}
