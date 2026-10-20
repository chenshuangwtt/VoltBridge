package com.voltbridge.protocol.yunkuaichong.message;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 云快充 V1.5 0x19 的协议内消息模型，仅在协议模块内部使用。
 */
public record YunKuaiChongEndChargeMessage(String transactionId, String deviceCode,
                                           int connectorNo, int endSoc,
                                           BigDecimal minimumCellVoltage,
                                           BigDecimal maximumCellVoltage,
                                           int minimumTemperature, int maximumTemperature,
                                           Duration chargingDuration, BigDecimal outputEnergyKwh,
                                           String chargerCode) {
}
