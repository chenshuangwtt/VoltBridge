package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.infra.persistence.entity.ChargingTransactionEntity;
import com.voltbridge.infra.persistence.mapper.ChargingTransactionMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 充电交易领域仓储的 PostgreSQL/MyBatis 实现。
 *
 * <p>负责领域聚合与表实体互转，并通过版本字段检测并发覆盖；交易唯一性和活动连接器
 * 唯一性由数据库部分唯一索引做最终保障。</p>
 */
@Repository
public class MybatisChargingTransactionRepository implements ChargingTransactionRepository {

    private final ChargingTransactionMapper mapper;

    public MybatisChargingTransactionRepository(ChargingTransactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(ChargingTransaction transaction) {
        ChargingTransactionEntity entity = toEntity(transaction);
        // 新聚合插入，已存在聚合按 version 乐观锁更新。
        if (mapper.selectById(transaction.id()) == null) {
            mapper.insert(entity);
        } else if (mapper.updateById(entity) == 0) {
            throw new IllegalStateException("Charging transaction concurrent update: " + transaction.id());
        }
    }

    @Override
    public Optional<ChargingTransaction> findById(UUID id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<ChargingTransaction> findByCommandId(UUID commandId) {
        return one(new QueryWrapper<ChargingTransactionEntity>().eq("command_id", commandId));
    }

    @Override
    public Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol,
                                                                     UUID deviceId,
                                                                     String protocolTransactionId) {
        // 与数据库唯一索引使用相同组合，支持重启后的精确恢复和重传幂等。
        return one(new QueryWrapper<ChargingTransactionEntity>()
                .eq("protocol", protocol.name()).eq("device_id", deviceId)
                .eq("protocol_transaction_id", protocolTransactionId));
    }

    @Override
    public Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId) {
        // 只把非终态交易视为连接器当前活动交易。
        return one(new QueryWrapper<ChargingTransactionEntity>()
                .eq("device_id", deviceId).eq("connector_id", connectorId)
                .in("status", ChargingTransactionStatus.STARTING.name(),
                        ChargingTransactionStatus.CHARGING.name(), ChargingTransactionStatus.ENDING.name()));
    }

    @Override
    public List<ChargingTransaction> find(String deviceCode, Integer connectorNo,
                                          ChargingTransactionStatus status) {
        QueryWrapper<ChargingTransactionEntity> query = new QueryWrapper<>();
        if (deviceCode != null && !deviceCode.isBlank()) {
            query.eq("device_code", deviceCode);
        }
        if (connectorNo != null) {
            query.eq("connector_no", connectorNo);
        }
        if (status != null) {
            query.eq("status", status.name());
        }
        query.orderByDesc("created_at");
        return mapper.selectList(query).stream().map(this::toDomain).toList();
    }

    private Optional<ChargingTransaction> one(QueryWrapper<ChargingTransactionEntity> query) {
        return Optional.ofNullable(mapper.selectOne(query)).map(this::toDomain);
    }

    private ChargingTransactionEntity toEntity(ChargingTransaction transaction) {
        // 持久化模型保持扁平，枚举写入稳定名称，Duration 统一换算为秒。
        ChargingTransactionEntity entity = new ChargingTransactionEntity();
        entity.id = transaction.id(); entity.tenantId = transaction.tenantId();
        entity.deviceId = transaction.deviceId(); entity.deviceCode = transaction.deviceCode();
        entity.connectorId = transaction.connectorId(); entity.connectorNo = transaction.connectorNo();
        entity.commandId = transaction.commandId(); entity.protocol = transaction.protocol().name();
        entity.protocolTransactionId = transaction.protocolTransactionId(); entity.businessRef = transaction.businessRef();
        entity.status = transaction.status().name(); entity.startedAt = transaction.startedAt();
        entity.endedAt = transaction.endedAt(); entity.startMeterKwh = transaction.startMeterKwh();
        entity.endMeterKwh = transaction.endMeterKwh(); entity.energyKwh = transaction.energyKwh();
        entity.startSoc = transaction.startSoc(); entity.latestSoc = transaction.latestSoc(); entity.endSoc = transaction.endSoc();
        entity.chargingDurationSeconds = transaction.chargingDuration() == null ? null : transaction.chargingDuration().toSeconds();
        entity.totalAmount = transaction.totalAmount();
        entity.stopCategory = transaction.stopCategory() == null ? null : transaction.stopCategory().name();
        entity.rawStopCode = transaction.rawStopCode(); entity.rawStopDetail = transaction.rawStopDetail();
        entity.vehicleVin = transaction.vehicleVin();
        entity.startMethod = transaction.startMethod() == null ? null : transaction.startMethod().name();
        entity.lastProgressAt = transaction.lastProgressAt(); entity.finalRecordReceivedAt = transaction.finalRecordReceivedAt();
        entity.rawFinalRecord = transaction.rawFinalRecord(); entity.additionalInfo = transaction.additionalInfo();
        entity.createdAt = transaction.createdAt(); entity.updatedAt = transaction.updatedAt(); entity.version = transaction.version();
        return entity;
    }

    private ChargingTransaction toDomain(ChargingTransactionEntity entity) {
        // 通过 restore 还原快照，禁止查询过程意外触发领域状态变化。
        return ChargingTransaction.restore(entity.id, entity.tenantId, entity.deviceId, entity.deviceCode,
                entity.connectorId, entity.connectorNo, DeviceProtocol.valueOf(entity.protocol), entity.commandId,
                entity.businessRef, entity.protocolTransactionId, ChargingTransactionStatus.valueOf(entity.status),
                entity.startedAt, entity.endedAt, entity.startMeterKwh, entity.endMeterKwh, entity.energyKwh,
                entity.startSoc, entity.latestSoc, entity.endSoc,
                entity.chargingDurationSeconds == null ? null : Duration.ofSeconds(entity.chargingDurationSeconds),
                entity.totalAmount, entity.stopCategory == null ? null : ChargingStopCategory.valueOf(entity.stopCategory),
                entity.rawStopCode, entity.rawStopDetail, entity.vehicleVin,
                entity.startMethod == null ? null : ChargingStartMethod.valueOf(entity.startMethod),
                entity.lastProgressAt, entity.finalRecordReceivedAt, entity.rawFinalRecord,
                entity.additionalInfo, entity.createdAt, entity.updatedAt, entity.version == null ? 0 : entity.version);
    }
}
