package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.voltbridge.infra.persistence.typehandler.PostgreSqlJsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@TableName(value = "device_command", autoResultMap = true)
public class DeviceCommandEntity {

    @TableId(type = IdType.INPUT)
    public UUID id;
    public UUID tenantId;
    public UUID deviceId;
    public String deviceCode;
    public UUID connectorId;
    public String connectorNo;
    public String commandType;
    public String commandStatus;
    public String businessRef;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = PostgreSqlJsonbTypeHandler.class)
    public String requestPayload;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = PostgreSqlJsonbTypeHandler.class)
    public String responsePayload;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant sentAt;
    public Instant ackedAt;
    public Instant completedAt;
    public Instant expiredAt;
    public String failureCode;
    public String failureReason;
    @Version
    public Long version;
}
