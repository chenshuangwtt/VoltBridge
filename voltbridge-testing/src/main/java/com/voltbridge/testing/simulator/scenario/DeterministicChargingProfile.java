package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.ChargingSample;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 线性、可重复的进度曲线；相同配置永远生成相同 SOC、电量、电表和模拟时间。 */
public final class DeterministicChargingProfile {

    private final ChargingScenarioConfig config;

    public DeterministicChargingProfile(ChargingScenarioConfig config) {
        this.config = config;
    }

    public List<ChargingSample> samples() {
        int count = config.sampleCount();
        List<ChargingSample> result = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            BigDecimal fraction = BigDecimal.valueOf(index).divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
            int soc = config.initialSoc() + BigDecimal.valueOf(config.targetSoc() - config.initialSoc())
                    .multiply(fraction).setScale(0, RoundingMode.HALF_UP).intValue();
            BigDecimal energy = interpolate(config.initialEnergyKwh(), config.targetEnergyKwh(), fraction, 4);
            BigDecimal meter = config.initialMeterKwh().add(energy).setScale(4, RoundingMode.HALF_UP);
            Duration elapsed = config.simulatedDuration().multipliedBy(index).dividedBy(count);
            BigDecimal voltage = BigDecimal.valueOf(230).add(BigDecimal.valueOf(index % 3)).setScale(1);
            BigDecimal current = config.chargingPowerKw().multiply(BigDecimal.valueOf(1000))
                    .divide(voltage, 1, RoundingMode.HALF_UP);
            result.add(new ChargingSample(index, config.virtualStartedAt().plus(elapsed), elapsed, soc,
                    energy, meter, config.chargingPowerKw(), voltage, current));
        }
        return List.copyOf(result);
    }

    private BigDecimal interpolate(BigDecimal start, BigDecimal target, BigDecimal fraction, int scale) {
        return start.add(target.subtract(start).multiply(fraction)).setScale(scale, RoundingMode.HALF_UP);
    }
}
