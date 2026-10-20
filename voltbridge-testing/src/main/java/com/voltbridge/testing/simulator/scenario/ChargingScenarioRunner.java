package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.ChargingSample;
import com.voltbridge.testing.simulator.core.DeviceSimulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 协议无关的远程启停充场景编排器。
 *
 * <p>真实时间只用于控制报文节奏；每条采样的协议时间戳和数值均来自确定性虚拟时钟。
 * 其不访问平台内部处理器，只通过模拟器的真实连接与调用方提供的公开业务入口协作。</p>
 */
public final class ChargingScenarioRunner {

    public ScenarioResult runRemoteStartScenario(DeviceSimulator simulator, ChargingScenarioConfig config,
                                                 PlatformScenarioControl platform) throws Exception {
        Instant startedAt = Instant.now();
        DeterministicChargingProfile profile = new DeterministicChargingProfile(config);
        List<ChargingSample> samples = profile.samples();
        UUID startCommandId = null;
        UUID stopCommandId = null;
        try {
            simulator.connect();
            simulator.loginOrBoot();
            simulator.heartbeat();
            simulator.connectorReady();

            startCommandId = platform.requestRemoteStart();
            simulator.trace().add("Platform RemoteStart requested: commandId=" + startCommandId);
            simulator.awaitRemoteStart(config.progressInterval().plusSeconds(3));
            simulator.acceptRemoteStart();
            platform.awaitStartAcknowledged(startCommandId);

            simulator.beginCharging(samples.getFirst());
            platform.awaitStartSucceeded(startCommandId);
            platform.awaitTransactionCharging();

            int stopAt = Math.max(1, samples.size() / 2);
            for (int index = 1; index < samples.size(); index++) {
                awaitTick(config.progressInterval());
                ChargingSample sample = samples.get(index);
                simulator.sendChargingProgress(sample);
                if (config.completionMode() == com.voltbridge.testing.simulator.core.CompletionMode.REMOTE_STOP
                        && index == stopAt) {
                    stopCommandId = platform.requestRemoteStop();
                    simulator.trace().add("Platform RemoteStop requested: commandId=" + stopCommandId);
                    simulator.awaitRemoteStop(config.progressInterval().plusSeconds(3));
                    simulator.acceptRemoteStop();
                    platform.awaitStopAcknowledged(stopCommandId);
                    break;
                }
            }
            ChargingSample finalSample = samples.getLast();
            simulator.completeCharging(finalSample, config.completionMode(), config.duplicateFinalRecord());
            if (config.completionMode() == com.voltbridge.testing.simulator.core.CompletionMode.REMOTE_STOP) {
                platform.awaitStopSucceeded(stopCommandId);
            }
            platform.awaitTransactionCompleted();
            simulator.trace().add("Platform transaction COMPLETED");
            return new ScenarioResult(config.scenarioName(), simulator.protocol(), config.deviceCode(),
                    config.connectorNo(), startedAt, Instant.now(), startCommandId, stopCommandId,
                    platform.chargingTransactionId(), "COMPLETED", samples.size(),
                    finalSample.stateOfCharge(), finalSample.energyKwh(), simulator.trace().entries());
        } finally {
            simulator.disconnect();
        }
    }

    private void awaitTick(java.time.Duration interval) throws InterruptedException {
        CountDownLatch tick = new CountDownLatch(1);
        if (!tick.await(interval.toMillis(), TimeUnit.MILLISECONDS)) {
            return;
        }
    }
}
