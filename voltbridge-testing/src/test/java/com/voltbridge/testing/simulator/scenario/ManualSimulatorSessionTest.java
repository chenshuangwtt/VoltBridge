package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.testing.simulator.core.ChargingSample;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.core.DeviceSimulator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ManualSimulatorSessionTest {

    @Test
    void remoteStopKeepsOneConnectionForSecondCycleAndPeriodicHeartbeatUntilInterrupted() throws Exception {
        ManualSimulatorMain.Options options = ManualSimulatorMain.parseArguments(new String[] {
                "--protocol=ykc", "--remote-stop=true", "--real-duration-seconds=1",
                "--progress-interval-seconds=1", "--heartbeat-interval-seconds=1"
        });
        FakeDeviceSimulator simulator = new FakeDeviceSimulator(2);
        ManualSimulatorSession session = new ManualSimulatorSession(simulator, options);
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                session.run();
            } catch (Throwable failure) {
                runFailure.set(failure);
            }
        });

        runner.start();
        assertThat(simulator.waitingForNextRemoteStart.await(8, TimeUnit.SECONDS)).isTrue();
        assertThat(simulator.connectionCount.get()).isEqualTo(1);
        assertThat(simulator.remoteStartCount.get()).isEqualTo(2);
        assertThat(simulator.completedCycleCount.get()).isEqualTo(2);
        assertThat(simulator.acceptedRemoteStopCount.get()).isEqualTo(2);
        assertThat(simulator.periodicHeartbeatCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(simulator.disconnectCount.get()).isZero();

        runner.interrupt();
        runner.join(3_000);

        assertThat(runner.isAlive()).isFalse();
        assertThat(runFailure.get()).isNull();
        assertThat(simulator.disconnectCount.get()).isEqualTo(1);
    }

    @Test
    void explicitCloseDisconnectsAnIdleSimulatorExactlyOnce() throws Exception {
        ManualSimulatorMain.Options options = ManualSimulatorMain.parseArguments(new String[] {
                "--protocol=ocpp", "--heartbeat-interval-seconds=1"
        });
        FakeDeviceSimulator simulator = new FakeDeviceSimulator(0);
        ManualSimulatorSession session = new ManualSimulatorSession(simulator, options);
        Thread runner = new Thread(() -> {
            try {
                session.run();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        runner.start();
        assertThat(simulator.waitingForNextRemoteStart.await(3, TimeUnit.SECONDS)).isTrue();
        session.close();
        runner.join(3_000);

        assertThat(runner.isAlive()).isFalse();
        assertThat(simulator.disconnectCount.get()).isEqualTo(1);
    }

    private static final class FakeDeviceSimulator implements DeviceSimulator {
        private final int startsBeforeWaiting;
        private final AtomicInteger connectionCount = new AtomicInteger();
        private final AtomicInteger remoteStartCount = new AtomicInteger();
        private final AtomicInteger remoteStartPollCount = new AtomicInteger();
        private final AtomicInteger completedCycleCount = new AtomicInteger();
        private final AtomicInteger acceptedRemoteStopCount = new AtomicInteger();
        private final AtomicInteger periodicHeartbeatCount = new AtomicInteger();
        private final AtomicInteger disconnectCount = new AtomicInteger();
        private final CountDownLatch waitingForNextRemoteStart = new CountDownLatch(1);
        private final CountDownLatch explicitDisconnect = new CountDownLatch(1);
        private final ScenarioTrace trace = new ScenarioTrace();
        private volatile boolean connected;

        private FakeDeviceSimulator(int startsBeforeWaiting) {
            this.startsBeforeWaiting = startsBeforeWaiting;
        }

        @Override public DeviceProtocol protocol() { return DeviceProtocol.YUN_KUAI_CHONG_150; }
        @Override public ScenarioTrace trace() { return trace; }
        @Override public void connect() { connected = true; connectionCount.incrementAndGet(); }
        @Override public void loginOrBoot() { }
        @Override public void heartbeat() { }
        @Override public void sendHeartbeat() throws IOException {
            if (!connected) throw new IOException("simulator is disconnected");
            periodicHeartbeatCount.incrementAndGet();
        }
        @Override public void connectorReady() { }
        @Override public void awaitRemoteStart(Duration timeout) { }
        @Override public boolean awaitRemoteStartIfAvailable(Duration timeout) throws InterruptedException {
            int poll = remoteStartPollCount.incrementAndGet();
            if (poll <= startsBeforeWaiting) {
                remoteStartCount.incrementAndGet();
                if (!connected) throw new IllegalStateException("connection should still be open");
                return true;
            }
            waitingForNextRemoteStart.countDown();
            explicitDisconnect.await();
            return false;
        }
        @Override public void acceptRemoteStart() { }
        @Override public void beginCharging(ChargingSample sample) { }
        @Override public void sendChargingProgress(ChargingSample sample) { }
        @Override public void awaitRemoteStop(Duration timeout) { }
        @Override public void acceptRemoteStop() { acceptedRemoteStopCount.incrementAndGet(); }
        @Override public void completeCharging(ChargingSample finalSample, CompletionMode mode,
                                               boolean duplicateFinalRecord) {
            if (!connected) throw new IllegalStateException("cycle completed after disconnect");
            completedCycleCount.incrementAndGet();
        }
        @Override public void disconnect() {
            connected = false;
            disconnectCount.incrementAndGet();
            explicitDisconnect.countDown();
        }
    }
}
