package com.voltbridge.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.voltbridge.infra.persistence.typehandler.PostgreSqlJsonbTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code charging_transaction} 表的 MyBatis 持久化模型。
 *
 * <p>该类型只负责数据库字段映射；状态机和幂等判断始终保留在领域聚合中。</p>
 */
@TableName(value = "charging_transaction", autoResultMap = true)
public class ChargingTransactionEntity {
    @TableId(type = IdType.INPUT)
    public UUID id;
    public UUID tenantId;
    public UUID deviceId;
    public String deviceCode;
    public UUID connectorId;
    public Integer connectorNo;
    public UUID commandId;
    public String protocol;
    public String protocolTransactionId;
    public String businessRef;
    public String status;
    public Instant startedAt;
    public Instant endedAt;
    public BigDecimal startMeterKwh;
    public BigDecimal endMeterKwh;
    public BigDecimal energyKwh;
    public Integer startSoc;
    public Integer latestSoc;
    public Integer endSoc;
    public Long chargingDurationSeconds;
    public BigDecimal totalAmount;
    public String stopCategory;
    public Integer rawStopCode;
    public String rawStopDetail;
    public String vehicleVin;
    public String startMethod;
    public Instant lastProgressAt;
    public Instant finalRecordReceivedAt;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = PostgreSqlJsonbTypeHandler.class)
    public String rawFinalRecord;
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = PostgreSqlJsonbTypeHandler.class)
    public String additionalInfo;
    public Instant createdAt;
    public Instant updatedAt;
    @Version
    public Long version;
}
