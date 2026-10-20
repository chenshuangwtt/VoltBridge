package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.device.model.DeviceProtocol;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 完整设备侧场景的结构化结果，避免仅依赖测试日志判断执行情况。 */
public record ScenarioResult(String scenarioName, DeviceProtocol protocol, String deviceCode, int connectorNo,
                             Instant startedAt, Instant finishedAt, UUID startCommandId, UUID stopCommandId,
                             UUID chargingTransactionId, String finalStatus, int samplesSent, int finalSoc,
                             BigDecimal finalEnergyKwh, List<String> trace) {
}
