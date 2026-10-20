package com.voltbridge.business.domain.billing;

/** 资费方案仅能由草稿显式启用，历史方案不会原地改价。 */
public enum TariffPlanStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
