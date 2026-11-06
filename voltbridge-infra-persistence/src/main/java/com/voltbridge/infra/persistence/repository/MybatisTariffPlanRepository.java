package com.voltbridge.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.infra.persistence.entity.TariffPeriodEntity;
import com.voltbridge.infra.persistence.entity.TariffPlanEntity;
import com.voltbridge.infra.persistence.mapper.TariffPeriodMapper;
import com.voltbridge.infra.persistence.mapper.TariffPlanMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL 资费仓储，保留业务 revision、日内时段和有效区间语义。 */
@Repository
public class MybatisTariffPlanRepository implements TariffPlanRepository {
    private final TariffPlanMapper plans;
    private final TariffPeriodMapper periods;

    public MybatisTariffPlanRepository(TariffPlanMapper plans, TariffPeriodMapper periods) {
        this.plans = plans;
        this.periods = periods;
    }

    @Override
    @Transactional
    public void save(TariffPlan plan) {
        TariffPlanEntity entity = entity(plan);
        if (plans.selectById(plan.id()) == null) {
            if (plan.role() == TariffRole.FALLBACK) {
                lockTenantForTariffLifecycle(plan.tenantId());
            }
            lockFamilyForRevision(plan.familyId());
            assertFamilyInvariant(plan);
            plans.insert(entity);
            for (TariffPeriod period : plan.periods()) {
                periods.insert(periodEntity(plan.id(), period));
            }
            plans.reserveRevision(plan.familyId(), plan.revision());
        } else if (plans.updateById(entity) == 0) {
            throw new VoltBridgeException("TARIFF_VERSION_CONFLICT",
                    "Tariff plan changed concurrently: " + plan.id());
        }
    }

    @Override
    public Optional<TariffPlan> findById(UUID id) {
        return Optional.ofNullable(plans.selectById(id)).map(this::domain);
    }

    @Override
    public List<TariffPlan> findByTenantId(UUID tenantId) {
        return plans.selectList(new QueryWrapper<TariffPlanEntity>()
                        .eq("tenant_id", tenantId).orderByDesc("created_at", "revision"))
                .stream().map(this::domain).toList();
    }

    @Override
    public List<TariffPlan> findEffectiveAt(UUID tenantId, TariffRole role, Instant effectiveAt) {
        QueryWrapper<TariffPlanEntity> query = new QueryWrapper<TariffPlanEntity>()
                .eq("tenant_id", tenantId)
                .eq("role", role.name())
                .in("status", TariffPlanStatus.ACTIVE.name(), TariffPlanStatus.RETIRED.name())
                .isNotNull("activated_at")
                .le("effective_from", effectiveAt)
                .and(range -> range.isNull("effective_to").or().gt("effective_to", effectiveAt))
                .and(retirement -> retirement.isNull("retired_at").or().gt("retired_at", effectiveAt));
        return plans.selectList(query).stream().map(this::domain).toList();
    }

    @Override
    public List<TariffPlan> findActiveOverlapping(UUID tenantId, Instant effectiveFrom, Instant effectiveTo,
                                                  UUID excludingPlanId) {
        QueryWrapper<TariffPlanEntity> query = new QueryWrapper<TariffPlanEntity>()
                .eq("tenant_id", tenantId)
                .eq("role", TariffRole.SCHEDULED.name())
                .eq("status", TariffPlanStatus.ACTIVE.name())
                .and(overlap -> overlap.isNull("effective_to").or().gt("effective_to", effectiveFrom));
        if (excludingPlanId != null) query.ne("id", excludingPlanId);
        if (effectiveTo != null) query.lt("effective_from", effectiveTo);
        return plans.selectList(query).stream().map(this::domain).toList();
    }

    @Override
    public void lockTenantForTariffLifecycle(UUID tenantId) {
        plans.lockScope("tariff-lifecycle:" + tenantId);
    }

    @Override
    public void lockFamilyForRevision(UUID familyId) {
        plans.lockScope("tariff-revision:" + familyId);
    }

    @Override
    public int nextRevision(UUID familyId) {
        plans.initializeRevisionCounter(familyId);
        if (plans.incrementRevisionCounter(familyId) != 1) {
            throw new VoltBridgeException("TARIFF_REVISION_ALLOCATION_FAILED",
                    "Unable to allocate the next tariff revision for family " + familyId);
        }
        return plans.revisionCounter(familyId);
    }

    @Override
    @Transactional
    public boolean delete(UUID id) {
        periods.deleteByTariffPlanId(id);
        return plans.deleteById(id) > 0;
    }

    private TariffPlanEntity entity(TariffPlan plan) {
        TariffPlanEntity value = new TariffPlanEntity();
        value.id = plan.id();
        value.familyId = plan.familyId();
        value.revision = plan.revision();
        value.tenantId = plan.tenantId();
        value.role = plan.role().name();
        value.name = plan.name();
        value.currency = plan.currency();
        value.pricingType = plan.pricingType().name();
        value.timezone = plan.timezone();
        value.effectiveFrom = plan.effectiveFrom();
        value.effectiveTo = plan.effectiveTo();
        value.status = plan.status().name();
        value.activatedAt = plan.activatedAt();
        value.retiredAt = plan.retiredAt();
        value.createdAt = plan.createdAt();
        value.version = plan.version();
        return value;
    }

    private TariffPeriodEntity periodEntity(UUID planId, TariffPeriod period) {
        TariffPeriodEntity value = new TariffPeriodEntity();
        value.tariffPlanId = planId;
        value.sequenceNo = period.sequenceNo();
        value.periodType = period.periodType().name();
        value.startMinute = period.startMinute();
        value.endMinute = period.endMinute();
        value.energyPricePerKwh = period.energyPricePerKwh();
        value.serviceFeePerKwh = period.serviceFeePerKwh();
        return value;
    }

    private TariffPlan domain(TariffPlanEntity entity) {
        List<TariffPeriod> tariffPeriods = periods.findByTariffPlanId(entity.id).stream()
                .map(period -> new TariffPeriod(period.sequenceNo, TariffPeriodType.valueOf(period.periodType),
                        period.startMinute, period.endMinute, period.energyPricePerKwh,
                        period.serviceFeePerKwh))
                .toList();
        return TariffPlan.restore(entity.id, entity.familyId, entity.revision, entity.tenantId,
                TariffRole.valueOf(entity.role),
                entity.name, entity.currency, PricingType.valueOf(entity.pricingType), entity.timezone,
                tariffPeriods, entity.effectiveFrom, entity.effectiveTo,
                TariffPlanStatus.valueOf(entity.status), entity.activatedAt, entity.retiredAt, entity.createdAt,
                entity.version == null ? 0 : entity.version);
    }

    private void assertFamilyInvariant(TariffPlan plan) {
        List<TariffPlanEntity> familyPlans = plans.selectList(new QueryWrapper<TariffPlanEntity>()
                .eq("family_id", plan.familyId()));
        if (familyPlans.stream().anyMatch(existing -> !plan.tenantId().equals(existing.tenantId)
                || !plan.role().name().equals(existing.role))) {
            throw new VoltBridgeException("TARIFF_FAMILY_ROLE_MISMATCH",
                    "All revisions in a tariff family must retain the same tenant and role");
        }
        if (plan.role() == TariffRole.FALLBACK) {
            List<TariffPlanEntity> tenantFallbacks = plans.selectList(new QueryWrapper<TariffPlanEntity>()
                    .eq("tenant_id", plan.tenantId()).eq("role", TariffRole.FALLBACK.name()));
            if (tenantFallbacks.stream().anyMatch(existing -> !plan.familyId().equals(existing.familyId))) {
                throw new VoltBridgeException("FALLBACK_FAMILY_ALREADY_EXISTS",
                        "A tenant can have only one fallback tariff family");
            }
        }
    }
}
