package com.voltbridge.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.ChargingConnectorEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChargingConnectorMapper extends BaseMapper<ChargingConnectorEntity> {
}
