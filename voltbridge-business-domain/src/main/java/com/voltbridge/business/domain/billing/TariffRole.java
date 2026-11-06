package com.voltbridge.business.domain.billing;

/** Distinguishes the tenant-wide safety tariff from operator-scheduled pricing. */
public enum TariffRole {
    FALLBACK,
    SCHEDULED
}
