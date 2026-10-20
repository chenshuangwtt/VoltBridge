package com.voltbridge.protocol.yunkuaichong.message;

import java.math.BigDecimal;
import java.time.Duration;

public record YunKuaiChongRealtimeDataMessage(String transactionId, String deviceCode, int connectorNo,
                                              int rawStatus, int homingStatus, boolean plugged,
                                              BigDecimal outputVoltage, BigDecimal outputCurrent,
                                              int stateOfCharge, Duration chargingDuration,
                                              BigDecimal energyKwh, BigDecimal chargingAmount,
                                              int faultBits) {
}
