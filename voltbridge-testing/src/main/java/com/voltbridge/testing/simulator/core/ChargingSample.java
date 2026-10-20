package com.voltbridge.testing.simulator.core;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** 一条由模拟时钟产生、可编码为任意协议进度报文的规范充电采样。 */
public record ChargingSample(int sequence, Instant occurredAt, Duration simulatedElapsed,
                             int stateOfCharge, BigDecimal energyKwh, BigDecimal meterKwh,
                             BigDecimal powerKw, BigDecimal voltage, BigDecimal current) {
}
