package com.voltbridge.infra.persistence.mapper;

import com.voltbridge.infra.persistence.entity.ChargingMeterSampleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ChargingMeterSampleMapper {
    @Insert("""
            INSERT INTO charging_meter_sample (event_id, transaction_id, occurred_at, energy_kwh,
                                               power_kw, state_of_charge, protocol)
            VALUES (#{eventId}, #{transactionId}, #{occurredAt}, #{energyKwh}, #{powerKw}, #{stateOfCharge}, #{protocol})
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertIfAbsent(ChargingMeterSampleEntity entity);

    @Select("SELECT event_id AS eventId, transaction_id AS transactionId, occurred_at AS occurredAt, "
            + "energy_kwh AS energyKwh, power_kw AS powerKw, state_of_charge AS stateOfCharge, "
            + "protocol, created_at AS createdAt FROM charging_meter_sample "
            + "WHERE transaction_id = #{transactionId} ORDER BY occurred_at, event_id")
    List<ChargingMeterSampleEntity> findByTransactionId(@Param("transactionId") UUID transactionId);
}
