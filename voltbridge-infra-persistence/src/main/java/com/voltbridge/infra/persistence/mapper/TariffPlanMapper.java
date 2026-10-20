package com.voltbridge.infra.persistence.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.TariffPlanEntity;
import org.apache.ibatis.annotations.Mapper;
/** 资费方案基础 Mapper。 */
@Mapper public interface TariffPlanMapper extends BaseMapper<TariffPlanEntity> { }
