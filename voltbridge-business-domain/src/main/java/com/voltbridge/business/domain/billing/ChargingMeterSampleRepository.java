package com.voltbridge.business.domain.billing;

import java.util.List;
import java.util.UUID;

/** 账单所需的交易累计电量样本仓储。 */
public interface ChargingMeterSampleRepository {
    /** @return true only when this event ID was inserted for the first time. */
    boolean saveIfAbsent(ChargingMeterSample sample);

    List<ChargingMeterSample> findByTransactionId(UUID transactionId);
}
