package com.voltbridge.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.ChargingTransactionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 充电交易表的 MyBatis-Plus 基础数据访问接口。 */
@Mapper
public interface ChargingTransactionMapper extends BaseMapper<ChargingTransactionEntity> {
}
