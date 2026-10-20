package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandStatus;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.infra.persistence.entity.DeviceCommandEntity;
import com.voltbridge.infra.persistence.mapper.DeviceCommandMapper;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 设备命令仓储的 PostgreSQL/MyBatis 实现，包含停充命令并发幂等保护。 */
@Repository
public class MybatisDeviceCommandRepository implements DeviceCommandRepository {

    private final DeviceCommandMapper mapper;

    public MybatisDeviceCommandRepository(DeviceCommandMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(DeviceCommand command) {
        DeviceCommandEntity entity = toEntity(command);
        if (mapper.selectById(command.id()) == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    @Override
    public Optional<DeviceCommand> findById(UUID commandId) {
        return Optional.ofNullable(mapper.selectById(commandId)).map(this::toDomain);
    }

    @Override
    public List<DeviceCommand> findSentExpiredBefore(Instant now) {
        return mapper.selectList(new QueryWrapper<DeviceCommandEntity>()
                        .eq("command_status", DeviceCommandStatus.SENT.name())
                        .le("expired_at", now))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<DeviceCommand> findActiveByTypeAndBusinessRef(DeviceCommandType type,
                                                                   String businessRef) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<DeviceCommandEntity>()
                        .eq("command_type", type.name())
                        .eq("business_ref", businessRef)
                        .in("command_status", DeviceCommandStatus.CREATED.name(),
                                DeviceCommandStatus.SENT.name(), DeviceCommandStatus.ACKED.name(),
                                DeviceCommandStatus.EXECUTING.name())
                        .orderByDesc("created_at").last("LIMIT 1")))
                .map(this::toDomain);
    }

    @Override
    public DeviceCommand saveOrGetActive(DeviceCommand candidate) {
        Optional<DeviceCommand> existing = findActiveByTypeAndBusinessRef(
                candidate.type(), candidate.businessRef());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            mapper.insert(toEntity(candidate));
            return candidate;
        } catch (DuplicateKeyException exception) {
            return findActiveByTypeAndBusinessRef(candidate.type(), candidate.businessRef())
                    .orElseThrow(() -> exception);
        }
    }

    private DeviceCommandEntity toEntity(DeviceCommand command) {
        DeviceCommandEntity entity = new DeviceCommandEntity();
        entity.id = command.id();
        entity.tenantId = command.tenantId();
        entity.deviceId = command.deviceId();
        entity.deviceCode = command.deviceCode();
        entity.connectorId = command.connectorId();
        entity.connectorNo = command.connectorNo();
        entity.commandType = command.type().name();
        entity.commandStatus = command.status().name();
        entity.businessRef = command.businessRef();
        entity.requestPayload = command.requestPayload();
        entity.responsePayload = command.responsePayload();
        entity.createdAt = command.createdAt();
        entity.updatedAt = command.updatedAt();
        entity.sentAt = command.sentAt();
        entity.ackedAt = command.ackedAt();
        entity.completedAt = command.completedAt();
        entity.expiredAt = command.expiredAt();
        entity.failureCode = command.failureCode();
        entity.failureReason = command.failureReason();
        entity.version = command.version();
        return entity;
    }

    private DeviceCommand toDomain(DeviceCommandEntity entity) {
        return DeviceCommand.restore(entity.id, entity.tenantId, entity.deviceId, entity.deviceCode,
                entity.connectorId, entity.connectorNo, DeviceCommandType.valueOf(entity.commandType),
                DeviceCommandStatus.valueOf(entity.commandStatus), entity.businessRef,
                entity.requestPayload, entity.responsePayload, entity.createdAt,
                entity.updatedAt, entity.sentAt, entity.ackedAt, entity.completedAt, entity.expiredAt,
                entity.failureCode, entity.failureReason, entity.version == null ? 0 : entity.version);
    }
}
