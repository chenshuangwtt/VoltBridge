package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.infra.persistence.entity.ChargingDeviceEntity;
import com.voltbridge.infra.persistence.mapper.ChargingDeviceMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MybatisChargingDeviceRepository implements ChargingDeviceRepository {

    private final ChargingDeviceMapper mapper;

    public MybatisChargingDeviceRepository(ChargingDeviceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ChargingDevice> findById(UUID id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public Optional<ChargingDevice> findByDeviceCode(String deviceCode) {
        ChargingDeviceEntity entity = mapper.selectOne(new QueryWrapper<ChargingDeviceEntity>()
                .eq("device_code", deviceCode));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public boolean updateStatus(String deviceCode, String status, Instant updatedAt) {
        return mapper.update(new UpdateWrapper<ChargingDeviceEntity>()
                .eq("device_code", deviceCode)
                .set("status", status)
                .set("updated_at", updatedAt)
                .setSql("version = version + 1")) > 0;
    }

    private ChargingDevice toDomain(ChargingDeviceEntity entity) {
        return new ChargingDevice(entity.id, entity.tenantId, entity.stationId,
                entity.deviceCode, entity.deviceName, DeviceProtocol.valueOf(entity.protocol),
                entity.protocolVersion, entity.status, entity.createdAt, entity.updatedAt,
                entity.version == null ? 0 : entity.version);
    }
}
