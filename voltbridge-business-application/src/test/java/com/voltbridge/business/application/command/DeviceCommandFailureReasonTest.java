package com.voltbridge.business.application.command;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandFailureReason;
import com.voltbridge.business.domain.command.DeviceCommandStatus;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.common.exception.VoltBridgeException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCommandFailureReasonTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Test
    void preservesReasonShorterThanColumnLimit() {
        String reason = "x".repeat(DeviceCommandFailureReason.MAX_LENGTH - 1);
        DeviceCommand command = command();

        command.markFailed("FAILURE", reason, "{}", NOW);

        assertThat(command.failureReason()).isEqualTo(reason);
    }

    @Test
    void preservesReasonAtColumnLimit() {
        String reason = "x".repeat(DeviceCommandFailureReason.MAX_LENGTH);
        DeviceCommand command = command();

        command.markFailed("FAILURE", reason, "{}", NOW);

        assertThat(command.failureReason()).isEqualTo(reason).hasSize(DeviceCommandFailureReason.MAX_LENGTH);
    }

    @Test
    void truncatesLongReasonsForBothFailureTransitions() {
        String reason = "x".repeat(DeviceCommandFailureReason.MAX_LENGTH + 20);
        DeviceCommand failed = command();
        DeviceCommand acknowledgementFailed = command();
        acknowledgementFailed.markSent(NOW);

        failed.markFailed("FAILURE", reason, "{}", NOW);
        acknowledgementFailed.markAcknowledgementFailed("REJECTED", reason, "{}", NOW);

        assertThat(failed.failureReason()).hasSize(DeviceCommandFailureReason.MAX_LENGTH);
        assertThat(acknowledgementFailed.failureReason()).hasSize(DeviceCommandFailureReason.MAX_LENGTH);
    }

    @Test
    void acceptsNullAndDoesNotSplitUnicodeCodePoints() {
        assertThat(DeviceCommandFailureReason.normalize(null)).isNull();
        String reason = "😀".repeat(DeviceCommandFailureReason.MAX_LENGTH + 1);

        String normalized = DeviceCommandFailureReason.normalize(reason);

        assertThat(normalized.codePointCount(0, normalized.length()))
                .isEqualTo(DeviceCommandFailureReason.MAX_LENGTH);
        assertThat(normalized).isEqualTo("😀".repeat(DeviceCommandFailureReason.MAX_LENGTH));
    }

    @Test
    void keepsVoltBridgeCodeAndMessageButHidesUnknownExceptionText() {
        VoltBridgeException expected = new VoltBridgeException("DEVICE_OFFLINE", "Device is offline");
        RuntimeException unexpected = new RuntimeException("sensitive infrastructure detail".repeat(20));

        assertThat(DeviceCommandFailure.code(expected, "FALLBACK")).isEqualTo("DEVICE_OFFLINE");
        assertThat(DeviceCommandFailure.reason(expected)).isEqualTo("Device is offline");
        assertThat(DeviceCommandFailure.code(unexpected, "DISPATCH_FAILED")).isEqualTo("DISPATCH_FAILED");
        assertThat(DeviceCommandFailure.reason(unexpected)).isEqualTo("Unexpected internal error");
    }

    private DeviceCommand command() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        return DeviceCommand.create(tenantId, deviceId, "DEMO-DEVICE", UUID.randomUUID(), "01",
                DeviceCommandType.START_CHARGING, "DEMO-ORDER", "{}", NOW, NOW.plusSeconds(30));
    }
}
