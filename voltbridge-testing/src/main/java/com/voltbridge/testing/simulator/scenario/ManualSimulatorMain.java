package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.core.DeviceSimulator;
import com.voltbridge.testing.simulator.ocpp16.Ocpp16Simulator;
import com.voltbridge.testing.simulator.yunkuaichong.YunKuaiChongV150Simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Manual entry point for driving the existing protocol simulators against a local VoltBridge instance. */
public final class ManualSimulatorMain {

    private static final Set<String> ALLOWED_OPTIONS = Set.of(
            "protocol", "host", "port", "device", "connector", "remote-stop",
            "real-duration-seconds", "simulated-duration-minutes", "progress-interval-seconds",
            "heartbeat-interval-seconds", "exit-after-cycle");

    private ManualSimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            printUsage();
            return;
        }

        final Options options;
        try {
            options = parseArguments(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid simulator argument: " + exception.getMessage());
            System.err.println("Run with --help for usage.");
            System.exit(2);
            return;
        }

        DeviceSimulator simulator = options.protocol().equals("ykc")
                ? new YunKuaiChongV150Simulator(options.host(), options.port(), options.device(), options.connector())
                : new Ocpp16Simulator(options.host(), options.port(), "/ocpp", options.device(), options.connector());

        printConnectionSummary(options);
        ManualSimulatorSession session = new ManualSimulatorSession(simulator, options);
        Thread shutdownHook = new Thread(() -> {
            System.out.println("Shutting down simulator...");
            session.closeQuietly();
            System.out.println("Device disconnected.");
        }, "voltbridge-simulator-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            session.run();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shutdownInProgress) {
                // Ctrl+C already ran the shutdown hook.
            }
            session.closeQuietly();
        }
    }

    static Options parseArguments(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String argument : args) {
            if (argument == null || !argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException("expected --key=value, received: " + argument);
            }
            String[] pair = argument.substring(2).split("=", 2);
            String key = pair[0];
            String value = pair[1];
            if (!ALLOWED_OPTIONS.contains(key)) {
                throw new IllegalArgumentException("unknown option --" + key);
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("--" + key + " requires a value");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("option --" + key + " was specified more than once");
            }
        }

        String protocol = values.getOrDefault("protocol", "ocpp").toLowerCase(Locale.ROOT);
        if (!protocol.equals("ocpp") && !protocol.equals("ykc")) {
            throw new IllegalArgumentException("--protocol must be ocpp or ykc");
        }
        String host = values.getOrDefault("host", "127.0.0.1");
        String device = values.getOrDefault("device", protocol.equals("ykc") ? "20231212000010" : "OCPP-SIM-001");
        if (host.isBlank()) {
            throw new IllegalArgumentException("--host must not be blank");
        }
        if (device.isBlank()) {
            throw new IllegalArgumentException("--device must not be blank");
        }

        int port = positiveInt(values.getOrDefault("port", protocol.equals("ykc") ? "9000" : "9100"),
                "--port", 65535);
        int connector = positiveInt(values.getOrDefault("connector", "1"), "--connector", 99);
        boolean remoteStop = strictBoolean(values.getOrDefault("remote-stop", "false"), "--remote-stop");
        long realDurationSeconds = positiveLong(values.getOrDefault("real-duration-seconds", "30"),
                "--real-duration-seconds");
        long simulatedDurationMinutes = positiveLong(values.getOrDefault("simulated-duration-minutes", "30"),
                "--simulated-duration-minutes");
        long progressIntervalSeconds = positiveLong(values.getOrDefault("progress-interval-seconds", "2"),
                "--progress-interval-seconds");
        long heartbeatIntervalSeconds = values.containsKey("heartbeat-interval-seconds")
                ? positiveLong(values.get("heartbeat-interval-seconds"), "--heartbeat-interval-seconds") : 0;
        boolean exitAfterCycle = strictBoolean(values.getOrDefault("exit-after-cycle", "false"),
                "--exit-after-cycle");

        return new Options(protocol, host, port, device, connector,
                remoteStop ? CompletionMode.REMOTE_STOP : CompletionMode.NATURAL_TARGET,
                realDurationSeconds, simulatedDurationMinutes, progressIntervalSeconds,
                heartbeatIntervalSeconds, exitAfterCycle);
    }

    static ChargingScenarioConfig scenarioConfig(Options options, Instant cycleStartedAt) {
        return new ChargingScenarioConfig("manual-" + options.protocol(), options.device(), options.connector(),
                Duration.ofSeconds(options.realDurationSeconds()),
                Duration.ofMinutes(options.simulatedDurationMinutes()),
                Duration.ofSeconds(options.progressIntervalSeconds()),
                20, 80, java.math.BigDecimal.ZERO.setScale(4), new java.math.BigDecimal("6.0000"),
                new java.math.BigDecimal("12345.0000"), new java.math.BigDecimal("7.2"), options.completionMode(),
                cycleStartedAt, false);
    }

    private static void printConnectionSummary(Options options) {
        System.out.println("VoltBridge Manual Device Simulator");
        System.out.printf("Protocol: %s | Device: %s | Connector: %02d%n",
                options.protocol().toUpperCase(Locale.ROOT), options.device(), options.connector());
        System.out.printf("Target: %s:%d%s | Completion: %s%n", options.host(), options.port(),
                options.protocol().equals("ocpp") ? "/ocpp" : "",
                options.completionMode() == CompletionMode.REMOTE_STOP ? "remote stop" : "natural completion");
        System.out.println("Lifecycle: long-lived; press Ctrl+C to disconnect after any charging cycle.");
        System.out.println("Connecting to VoltBridge...");
    }

    private static int positiveInt(String value, String option, int max) {
        long parsed = positiveLong(value, option);
        if (parsed > max) {
            throw new IllegalArgumentException(option + " must be between 1 and " + max);
        }
        return (int) parsed;
    }

    private static long positiveLong(String value, String option) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(option + " must be a positive integer");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be a positive integer");
        }
    }

    private static boolean strictBoolean(String value, String option) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(option + " must be true or false");
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar voltbridge-device-simulator.jar [options]");
        System.out.println("  --protocol=ocpp|ykc             Protocol (default: ocpp)");
        System.out.println("  --host=HOST                     VoltBridge host (default: 127.0.0.1)");
        System.out.println("  --port=PORT                     OCPP default 9100; YKC default 9000");
        System.out.println("  --device=CODE                   Device code (protocol-specific default)");
        System.out.println("  --connector=NUMBER              Connector 1..99 (default: 1)");
        System.out.println("  --remote-stop=true|false        Wait for Console/API stop (default: false)");
        System.out.println("  --real-duration-seconds=NUMBER  Real charging duration (default: 30)");
        System.out.println("  --simulated-duration-minutes=NUMBER  Simulated charging duration (default: 30)");
        System.out.println("  --progress-interval-seconds=NUMBER   Progress interval (default: 2)");
        System.out.println("  --heartbeat-interval-seconds=NUMBER  Heartbeat period (default: protocol setting)");
        System.out.println("  --exit-after-cycle=true|false        Disconnect after one cycle (default: false)");
    }

    record Options(String protocol, String host, int port, String device, int connector,
                   CompletionMode completionMode, long realDurationSeconds,
                   long simulatedDurationMinutes, long progressIntervalSeconds,
                   long heartbeatIntervalSeconds, boolean exitAfterCycle) {
    }
}
