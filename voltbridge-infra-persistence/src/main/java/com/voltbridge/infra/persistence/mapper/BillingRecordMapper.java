package com.voltbridge.infra.persistence.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.BillingRecordEntity;
import org.apache.ibatis.annotations.Mapper;
/** 账单头基础 Mapper。 */
@Mapper public interface BillingRecordMapper extends BaseMapper<BillingRecordEntity> { }
