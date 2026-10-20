package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.infra.persistence.entity.ChargingConnectorEntity;
import com.voltbridge.infra.persistence.mapper.ChargingConnectorMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MybatisConnectorRepository implements ConnectorRepository {

    private final ChargingConnectorMapper mapper;

    public MybatisConnectorRepository(ChargingConnectorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) {
        ChargingConnectorEntity entity = mapper.selectOne(new QueryWrapper<ChargingConnectorEntity>()
                .eq("device_id", deviceId)
                .eq("connector_no", connectorNo));
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    private Connector toDomain(ChargingConnectorEntity entity) {
        return new Connector(entity.id, entity.tenantId, entity.stationId, entity.deviceId,
                entity.connectorNo, entity.connectorCode, entity.connectorType, entity.ratedPower,
                entity.status, entity.createdAt, entity.updatedAt);
    }
}
