package com.voltbridge.infra.persistence.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voltbridge.infra.persistence.entity.BillingLineItemEntity;
import org.apache.ibatis.annotations.Mapper;
/** 账单项目基础 Mapper。 */
@Mapper public interface BillingLineItemMapper extends BaseMapper<BillingLineItemEntity> { }
