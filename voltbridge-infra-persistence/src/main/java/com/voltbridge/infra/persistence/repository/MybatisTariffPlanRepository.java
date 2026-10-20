package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.infra.persistence.entity.TariffPlanEntity;
import com.voltbridge.infra.persistence.mapper.TariffPlanMapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL 资费方案仓储，按版本字段更新并按开始时间解析生效方案。 */
@Repository
public class MybatisTariffPlanRepository implements TariffPlanRepository {
    private final TariffPlanMapper mapper;
    public MybatisTariffPlanRepository(TariffPlanMapper mapper) { this.mapper = mapper; }
    @Override public void save(TariffPlan plan) {
        TariffPlanEntity entity = entity(plan);
        if (mapper.selectById(plan.id()) == null) mapper.insert(entity);
        else if (mapper.updateById(entity) == 0) throw new IllegalStateException("Tariff plan concurrent update: " + plan.id());
    }
    @Override public Optional<TariffPlan> findById(UUID id) { return Optional.ofNullable(mapper.selectById(id)).map(this::domain); }
    @Override public List<TariffPlan> findByTenantId(UUID tenantId) { return mapper.selectList(new QueryWrapper<TariffPlanEntity>().eq("tenant_id", tenantId).orderByDesc("created_at")).stream().map(this::domain).toList(); }
    @Override public Optional<TariffPlan> findEffectiveActive(UUID tenantId, Instant effectiveAt) {
        return mapper.selectList(new QueryWrapper<TariffPlanEntity>().eq("tenant_id", tenantId).eq("status", TariffPlanStatus.ACTIVE.name())
                        .le("effective_from", effectiveAt).and(q -> q.isNull("effective_to").or().gt("effective_to", effectiveAt))
                        .orderByDesc("effective_from").last("LIMIT 1")).stream().findFirst().map(this::domain);
    }
    private TariffPlanEntity entity(TariffPlan plan) { TariffPlanEntity value = new TariffPlanEntity(); value.id = plan.id(); value.tenantId = plan.tenantId(); value.name = plan.name(); value.currency = plan.currency(); value.pricingType = plan.pricingType().name(); value.energyPricePerKwh = plan.energyPricePerKwh(); value.serviceFeePerKwh = plan.serviceFeePerKwh(); value.effectiveFrom = plan.effectiveFrom(); value.effectiveTo = plan.effectiveTo(); value.status = plan.status().name(); value.activatedAt = plan.activatedAt(); value.createdAt = plan.createdAt(); value.version = plan.version(); return value; }
    private TariffPlan domain(TariffPlanEntity value) { return TariffPlan.restore(value.id, value.tenantId, value.name, value.currency, PricingType.valueOf(value.pricingType), value.energyPricePerKwh, value.serviceFeePerKwh, value.effectiveFrom, value.effectiveTo, TariffPlanStatus.valueOf(value.status), value.activatedAt, value.createdAt, value.version == null ? 0 : value.version); }
}
