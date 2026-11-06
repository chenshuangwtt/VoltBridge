package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.ChargingSample;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.core.DeviceSimulator;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one long-lived device connection and runs charging cycles until shutdown. */
final class ManualSimulatorSession implements AutoCloseable {

    private static final Duration REMOTE_START_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration REMOTE_COMMAND_TIMEOUT = Duration.ofMinutes(10);

    private final DeviceSimulator simulator;
    private final ManualSimulatorMain.Options options;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Exception> heartbeatFailure = new AtomicReference<>();
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile boolean connected;

    ManualSimulatorSession(DeviceSimulator simulator, ManualSimulatorMain.Options options) {
        this.simulator = simulator;
        this.options = options;
    }

    void run() throws Exception {
        try {
            simulator.connect();
            connected = true;
            System.out.println("Transport connected.");
            simulator.loginOrBoot();
            System.out.println("Login/BootNotification accepted.");
            simulator.heartbeat();
            simulator.connectorReady();
            Duration heartbeatInterval = options.heartbeatIntervalSeconds() > 0
                    ? Duration.ofSeconds(options.heartbeatIntervalSeconds()) : simulator.heartbeatInterval();
            startPeriodicHeartbeat(heartbeatInterval);
            System.out.println("Device online and connector ready. Start charging from VoltBridge Console/API.");
            System.out.println("Waiting for next RemoteStart...");

            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                checkHeartbeatFailure();
                boolean remoteStartReceived = simulator.awaitRemoteStartIfAvailable(REMOTE_START_POLL_INTERVAL);
                checkHeartbeatFailure();
                if (!remoteStartReceived) continue;

                runChargingCycle();
                if (options.exitAfterCycle()) {
                    System.out.println("Explicit exit after charging cycle requested.");
                    break;
                }
                System.out.println("[CHARGING COMPLETED]");
                System.out.println("Transaction finished.");
                System.out.println("Connector AVAILABLE.");
                System.out.println("Device remains online.");
                System.out.println();
                System.out.println("Waiting for next RemoteStart...");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (!closed.get()) throw exception;
        } finally {
            close();
        }
    }

    private void runChargingCycle() throws Exception {
        ChargingScenarioConfig config = ManualSimulatorMain.scenarioConfig(options, Instant.now());
        List<ChargingSample> samples = new DeterministicChargingProfile(config).samples();
        simulator.trace().add("Manual charging cycle started");
        simulator.acceptRemoteStart();
        simulator.beginCharging(samples.getFirst());
        for (int index = 1; index < samples.size(); index++) {
            TimeUnit.NANOSECONDS.sleep(config.progressInterval().toNanos());
            checkHeartbeatFailure();
            ChargingSample sample = samples.get(index);
            simulator.sendChargingProgress(sample);
            System.out.printf("Charging progress: SOC=%d%% energy=%s kWh%n",
                    sample.stateOfCharge(), sample.energyKwh().toPlainString());
            if (config.completionMode() == CompletionMode.REMOTE_STOP && index == samples.size() / 2) {
                System.out.println("Waiting for RemoteStop from VoltBridge Console/API...");
                simulator.awaitRemoteStop(REMOTE_COMMAND_TIMEOUT);
                checkHeartbeatFailure();
                simulator.acceptRemoteStop();
                System.out.println("RemoteStop accepted.");
                break;
            }
        }
        simulator.completeCharging(samples.getLast(), config.completionMode(), false);
        checkHeartbeatFailure();
        simulator.trace().add("Manual charging cycle completed; device remains connected");
        System.out.println(simulator.trace().render());
    }

    private void startPeriodicHeartbeat(Duration interval) {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "voltbridge-simulator-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1, interval.toMillis());
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                simulator.sendHeartbeat();
            } catch (Exception exception) {
                heartbeatFailure.compareAndSet(null, exception);
                throw new IllegalStateException("Periodic simulator heartbeat failed", exception);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        System.out.printf("Periodic heartbeat enabled: every %s%n", interval);
    }

    private void checkHeartbeatFailure() throws Exception {
        Exception failure = heartbeatFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Periodic simulator heartbeat failed", failure);
        }
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledExecutorService executor = heartbeatExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("Heartbeat task did not stop within two seconds.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (connected) {
            connected = false;
            simulator.disconnect();
        }
    }

    void closeQuietly() {
        try {
            close();
        } catch (Exception exception) {
            System.err.println("Simulator shutdown failed: " + exception.getMessage());
        }
    }
}
