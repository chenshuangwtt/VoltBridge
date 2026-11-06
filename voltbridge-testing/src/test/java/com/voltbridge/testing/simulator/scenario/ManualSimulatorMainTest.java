package com.voltbridge.testing.simulator.scenario;

import com.voltbridge.testing.simulator.core.CompletionMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualSimulatorMainTest {

    @Test
    void parsesProtocolSpecificDefaults() {
        ManualSimulatorMain.Options ocpp = ManualSimulatorMain.parseArguments(new String[0]);
        ManualSimulatorMain.Options ykc = ManualSimulatorMain.parseArguments(new String[] {"--protocol=YKC"});

        assertThat(ocpp.protocol()).isEqualTo("ocpp");
        assertThat(ocpp.port()).isEqualTo(9100);
        assertThat(ocpp.device()).isEqualTo("OCPP-SIM-001");
        assertThat(ocpp.heartbeatIntervalSeconds()).isZero();
        assertThat(ocpp.exitAfterCycle()).isFalse();
        assertThat(ykc.protocol()).isEqualTo("ykc");
        assertThat(ykc.port()).isEqualTo(9000);
        assertThat(ykc.device()).isEqualTo("20231212000010");
    }

    @Test
    void acceptsValidatedRemoteStopConfiguration() {
        ManualSimulatorMain.Options options = ManualSimulatorMain.parseArguments(new String[] {
                "--protocol=ocpp", "--host=localhost", "--port=12345", "--device=VB-DEMO-006",
                "--connector=2", "--remote-stop=true", "--real-duration-seconds=10",
                "--simulated-duration-minutes=60", "--progress-interval-seconds=1",
                "--heartbeat-interval-seconds=5", "--exit-after-cycle=true"
        });

        assertThat(options.host()).isEqualTo("localhost");
        assertThat(options.port()).isEqualTo(12345);
        assertThat(options.device()).isEqualTo("VB-DEMO-006");
        assertThat(options.connector()).isEqualTo(2);
        assertThat(options.completionMode()).isEqualTo(CompletionMode.REMOTE_STOP);
        assertThat(options.realDurationSeconds()).isEqualTo(10);
        assertThat(options.simulatedDurationMinutes()).isEqualTo(60);
        assertThat(options.progressIntervalSeconds()).isEqualTo(1);
        assertThat(options.heartbeatIntervalSeconds()).isEqualTo(5);
        assertThat(options.exitAfterCycle()).isTrue();
    }

    @Test
    void rejectsInvalidProtocolAndNumbers() {
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--protocol=other"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ocpp or ykc");
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--port=65536"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--port");
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--connector=0"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--connector");
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--real-duration-seconds=abc"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--real-duration-seconds");
    }

    @Test
    void rejectsUnknownMalformedDuplicateAndInvalidBooleanArguments() {
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--foo=bar"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown option");
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--protocol"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--key=value");
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {
                "--host=localhost", "--host=127.0.0.1"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");
        assertThatThrownBy(() -> ManualSimulatorMain.parseArguments(new String[] {"--remote-stop=yes"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("true or false");
    }
}
