package com.voltbridge.testing.simulator.core;

import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.testing.simulator.scenario.ScenarioTrace;

import java.time.Duration;

/**
 * 外部充电设备的高层行为契约。
 *
 * <p>实现类持有真实 socket/WebSocket，所有协议字节和 JSON 都在模拟器内生成；平台运行时代码
 * 不感知模拟环境。协议特有的帧字段由具体模拟器负责，场景编排只关心充电动作。</p>
 */
public interface DeviceSimulator extends AutoCloseable {
    DeviceProtocol protocol();
    ScenarioTrace trace();
    void connect() throws Exception;
    void loginOrBoot() throws Exception;
    void heartbeat() throws Exception;
    void connectorReady() throws Exception;
    void awaitRemoteStart(Duration timeout) throws Exception;
    void acceptRemoteStart() throws Exception;
    void beginCharging(ChargingSample sample) throws Exception;
    void sendChargingProgress(ChargingSample sample) throws Exception;
    void awaitRemoteStop(Duration timeout) throws Exception;
    void acceptRemoteStop() throws Exception;
    void completeCharging(ChargingSample finalSample, CompletionMode mode, boolean duplicateFinalRecord) throws Exception;
    void disconnect() throws Exception;

    @Override
    default void close() throws Exception { disconnect(); }
}
