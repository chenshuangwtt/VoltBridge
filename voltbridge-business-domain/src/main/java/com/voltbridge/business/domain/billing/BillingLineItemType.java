package com.voltbridge.business.domain.billing;

/** 可解释账单的项目类别；折扣、停车费和税费留作后续显式扩展。 */
public enum BillingLineItemType {
    ENERGY_FEE,
    SERVICE_FEE
}
