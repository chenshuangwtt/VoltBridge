package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.core.DeviceSimulator;
import com.voltbridge.testing.simulator.ocpp16.Ocpp16Simulator;
import com.voltbridge.testing.simulator.yunkuaichong.YunKuaiChongV150Simulator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * 面向本地 VoltBridge 的简单设备侧 CLI 入口。
 *
 * <p>启动后先登录/Boot 并等待操作者通过 VoltBridge REST API 发起远程启动；该工具不启动平台，也不含
 * 任何模拟分支。示例：{@code --protocol=ocpp --port=9100 --device=OCPP-SIM-001}。</p>
 */
public final class ManualSimulatorMain {

    private ManualSimulatorMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String protocol = options.getOrDefault("protocol", "ocpp");
        String host = options.getOrDefault("host", "127.0.0.1");
        int connector = Integer.parseInt(options.getOrDefault("connector", "1"));
        int port = Integer.parseInt(options.getOrDefault("port", protocol.equals("ykc") ? "9000" : "9100"));
        String device = options.getOrDefault("device", protocol.equals("ykc") ? "20231212000010" : "OCPP-SIM-001");
        CompletionMode completion = Boolean.parseBoolean(options.getOrDefault("remote-stop", "false"))
                ? CompletionMode.REMOTE_STOP : CompletionMode.NATURAL_TARGET;
        ChargingScenarioConfig config = new ChargingScenarioConfig("manual-" + protocol, device, connector,
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("real-duration-seconds", "30"))),
                Duration.ofMinutes(Long.parseLong(options.getOrDefault("simulated-duration-minutes", "30"))),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("progress-interval-seconds", "2"))),
                20, 80, BigDecimal.ZERO.setScale(4), new BigDecimal("6.0000"), new BigDecimal("12345.0000"),
                new BigDecimal("7.2"), completion, Instant.now(), false);
        DeviceSimulator simulator = protocol.equals("ykc")
                ? new YunKuaiChongV150Simulator(host, port, device, connector)
                : new Ocpp16Simulator(host, port, "/ocpp", device, connector);
        run(simulator, config);
    }

    private static void run(DeviceSimulator simulator, ChargingScenarioConfig config) throws Exception {
        List<com.voltbridge.testing.simulator.core.ChargingSample> samples = new DeterministicChargingProfile(config).samples();
        try (simulator) {
            simulator.connect(); simulator.loginOrBoot(); simulator.heartbeat(); simulator.connectorReady();
            System.out.println("Device online. Issue RemoteStart through VoltBridge REST/API now.");
            simulator.awaitRemoteStart(Duration.ofMinutes(10)); simulator.acceptRemoteStart(); simulator.beginCharging(samples.getFirst());
            for (int index = 1; index < samples.size(); index++) {
                LockSupport.parkNanos(config.progressInterval().toNanos());
                simulator.sendChargingProgress(samples.get(index));
                if (config.completionMode() == CompletionMode.REMOTE_STOP && index == samples.size() / 2) {
                    System.out.println("Waiting for RemoteStop through VoltBridge REST/API now.");
                    simulator.awaitRemoteStop(Duration.ofMinutes(10)); simulator.acceptRemoteStop(); break;
                }
            }
            simulator.completeCharging(samples.getLast(), config.completionMode(), false);
            System.out.println(simulator.trace().render());
        }
    }

    private static Map<String, String> parse(String[] args) {
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        for (String argument : args) {
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, received: " + argument);
            }
            String[] pair = argument.substring(2).split("=", 2);
            result.put(pair[0], pair[1]);
        }
        return Map.copyOf(result);
    }
}
