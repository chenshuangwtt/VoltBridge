package com.voltbridge.business.domain.billing;

import com.voltbridge.business.domain.transaction.ChargingTransaction;

/** 根据已完成交易的计价上下文解析实际生效的资费方案。 */
public interface TariffResolver {
    TariffPlan resolve(ChargingTransaction transaction);
}
