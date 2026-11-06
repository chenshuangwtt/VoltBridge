package com.voltbridge.infra.persistence.mapper;

import com.voltbridge.infra.persistence.entity.TariffPeriodEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TariffPeriodMapper {
    @Insert("""
            INSERT INTO tariff_period (tariff_plan_id, sequence_no, period_type, start_minute, end_minute,
                                      energy_price_per_kwh, service_fee_per_kwh)
            VALUES (#{tariffPlanId}, #{sequenceNo}, #{periodType}, #{startMinute}, #{endMinute},
                    #{energyPricePerKwh}, #{serviceFeePerKwh})
            """)
    int insert(TariffPeriodEntity entity);

    @Delete("DELETE FROM tariff_period WHERE tariff_plan_id = #{tariffPlanId}")
    int deleteByTariffPlanId(@Param("tariffPlanId") UUID tariffPlanId);

    @Select("SELECT tariff_plan_id AS tariffPlanId, sequence_no AS sequenceNo, period_type AS periodType, "
            + "start_minute AS startMinute, end_minute AS endMinute, energy_price_per_kwh AS energyPricePerKwh, "
            + "service_fee_per_kwh AS serviceFeePerKwh FROM tariff_period "
            + "WHERE tariff_plan_id = #{tariffPlanId} ORDER BY sequence_no")
    List<TariffPeriodEntity> findByTariffPlanId(@Param("tariffPlanId") UUID tariffPlanId);
}
