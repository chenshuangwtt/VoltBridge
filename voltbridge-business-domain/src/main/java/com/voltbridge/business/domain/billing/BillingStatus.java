package com.voltbridge.business.domain.billing;

/** 计费记录的最小生命周期；本期只生成不可变的 FINALIZED 结果。 */
public enum BillingStatus {
    PENDING,
    FINALIZED,
    FAILED
}
