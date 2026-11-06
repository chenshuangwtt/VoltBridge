package com.voltbridge.infra.persistence.repository;

import com.voltbridge.business.domain.billing.ChargingMeterSample;
import com.voltbridge.business.domain.billing.ChargingMeterSampleRepository;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.infra.persistence.entity.ChargingMeterSampleEntity;
import com.voltbridge.infra.persistence.mapper.ChargingMeterSampleMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** PostgreSQL 计费样本仓储：event_id 唯一约束提供跨节点幂等。 */
@Repository
public class MybatisChargingMeterSampleRepository implements ChargingMeterSampleRepository {
    private final ChargingMeterSampleMapper mapper;

    public MybatisChargingMeterSampleRepository(ChargingMeterSampleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean saveIfAbsent(ChargingMeterSample sample) {
        return mapper.insertIfAbsent(entity(sample)) == 1;
    }

    @Override
    public List<ChargingMeterSample> findByTransactionId(UUID transactionId) {
        return mapper.findByTransactionId(transactionId).stream().map(this::domain).toList();
    }

    private ChargingMeterSampleEntity entity(ChargingMeterSample sample) {
        ChargingMeterSampleEntity value = new ChargingMeterSampleEntity();
        value.eventId = sample.eventId();
        value.transactionId = sample.transactionId();
        value.occurredAt = sample.occurredAt();
        value.energyKwh = sample.energyKwh();
        value.powerKw = sample.powerKw();
        value.stateOfCharge = sample.stateOfCharge();
        value.protocol = sample.protocol().name();
        return value;
    }

    private ChargingMeterSample domain(ChargingMeterSampleEntity value) {
        return new ChargingMeterSample(value.eventId, value.transactionId, value.occurredAt,
                value.energyKwh, value.powerKw, value.stateOfCharge,
                DeviceProtocol.valueOf(value.protocol));
    }
}
