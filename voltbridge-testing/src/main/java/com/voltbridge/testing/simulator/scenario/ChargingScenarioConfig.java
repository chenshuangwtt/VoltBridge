package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.CompletionMode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 设备侧场景配置；真实耗时只控制测试节奏，协议时间戳与电量进度由 simulatedDuration 决定。
 */
public record ChargingScenarioConfig(String scenarioName, String deviceCode, int connectorNo,
                                     Duration realDuration, Duration simulatedDuration, Duration progressInterval,
                                     int initialSoc, int targetSoc, BigDecimal initialEnergyKwh,
                                     BigDecimal targetEnergyKwh, BigDecimal initialMeterKwh,
                                     BigDecimal chargingPowerKw, CompletionMode completionMode,
                                     Instant virtualStartedAt, boolean duplicateFinalRecord) {

    public ChargingScenarioConfig {
        Objects.requireNonNull(scenarioName, "scenarioName");
        Objects.requireNonNull(deviceCode, "deviceCode");
        Objects.requireNonNull(realDuration, "realDuration");
        Objects.requireNonNull(simulatedDuration, "simulatedDuration");
        Objects.requireNonNull(progressInterval, "progressInterval");
        Objects.requireNonNull(initialEnergyKwh, "initialEnergyKwh");
        Objects.requireNonNull(targetEnergyKwh, "targetEnergyKwh");
        Objects.requireNonNull(initialMeterKwh, "initialMeterKwh");
        Objects.requireNonNull(chargingPowerKw, "chargingPowerKw");
        Objects.requireNonNull(completionMode, "completionMode");
        Objects.requireNonNull(virtualStartedAt, "virtualStartedAt");
        if (connectorNo < 1 || realDuration.isNegative() || realDuration.isZero()
                || simulatedDuration.isNegative() || simulatedDuration.isZero()
                || progressInterval.isNegative() || progressInterval.isZero()
                || initialSoc < 0 || targetSoc > 100 || initialSoc > targetSoc
                || targetEnergyKwh.compareTo(initialEnergyKwh) < 0) {
            throw new IllegalArgumentException("Invalid deterministic charging scenario configuration");
        }
    }

    public int sampleCount() {
        long intervalMillis = Math.max(1, progressInterval.toMillis());
        return Math.max(2, (int) Math.ceil((double) realDuration.toMillis() / intervalMillis));
    }
}
