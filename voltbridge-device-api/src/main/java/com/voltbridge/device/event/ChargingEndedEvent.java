package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 协议无关的充电过程结束事件。
 *
 * <p>该事件只表示充电已经停止，不代表最终交易账单已经到达。</p>
 */
public record ChargingEndedEvent(UUID eventId, DeviceId deviceId, DeviceProtocol protocol,
                                 int connectorNo, String protocolTransactionId,
                                 Integer endSoc, BigDecimal minimumCellVoltage,
                                 BigDecimal maximumCellVoltage, Integer minimumTemperature,
                                 Integer maximumTemperature, Duration chargingDuration,
                                 BigDecimal outputEnergyKwh, String chargerCode,
                                 String additionalInfo, Instant occurredAt) implements DeviceEvent {
}
