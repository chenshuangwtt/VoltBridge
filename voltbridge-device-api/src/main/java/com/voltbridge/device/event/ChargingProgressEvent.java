package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record ChargingProgressEvent(UUID eventId, DeviceId deviceId, DeviceProtocol protocol, int connectorNo,
                                    String transactionId, BigDecimal outputVoltage,
                                    BigDecimal outputCurrent, BigDecimal outputPowerKw,
                                    Integer stateOfCharge,
                                    BigDecimal energyKwh, Duration chargingDuration,
                                    BigDecimal chargingAmount, String additionalInfo,
                                    Instant occurredAt) implements DeviceEvent {

    public ChargingProgressEvent(UUID eventId, DeviceId deviceId, int connectorNo,
                                 String transactionId, BigDecimal outputVoltage,
                                 BigDecimal outputCurrent, Integer stateOfCharge,
                                 BigDecimal energyKwh, Duration chargingDuration,
                                 BigDecimal chargingAmount, Instant occurredAt) {
        this(eventId, deviceId, DeviceProtocol.YUN_KUAI_CHONG_150, connectorNo,
                transactionId, outputVoltage, outputCurrent, null, stateOfCharge,
                energyKwh, chargingDuration, chargingAmount, "{}", occurredAt);
    }
}
