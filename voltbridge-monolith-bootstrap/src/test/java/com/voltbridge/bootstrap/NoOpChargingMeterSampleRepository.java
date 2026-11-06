package com.voltbridge.bootstrap;

import com.voltbridge.business.domain.billing.ChargingMeterSample;
import com.voltbridge.business.domain.billing.ChargingMeterSampleRepository;

import java.util.List;
import java.util.UUID;

/** Test adapter for protocol integration scenarios that do not exercise billing samples. */
final class NoOpChargingMeterSampleRepository implements ChargingMeterSampleRepository {
    @Override public boolean saveIfAbsent(ChargingMeterSample sample) { return true; }
    @Override public List<ChargingMeterSample> findByTransactionId(UUID transactionId) { return List.of(); }
}
