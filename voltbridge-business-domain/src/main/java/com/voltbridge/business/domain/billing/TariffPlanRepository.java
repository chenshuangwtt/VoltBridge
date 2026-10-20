package com.voltbridge.business.domain.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 资费方案仓储端口；有效时间查询以交易开始时间为输入，而不是当前系统时间。 */
public interface TariffPlanRepository {
    void save(TariffPlan tariffPlan);
    Optional<TariffPlan> findById(UUID id);
    List<TariffPlan> findByTenantId(UUID tenantId);
    Optional<TariffPlan> findEffectiveActive(UUID tenantId, Instant effectiveAt);
}
