package com.voltbridge.infra.cache;

import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineRuntimeStateStoreTest {

    private static final DeviceId DEVICE_ID = new DeviceId("20231212000010");

    @Test
    void refreshesLastSeenAndRejectsStaleSessionOfflineTransition() {
        CaffeineDeviceRuntimeStateStore store = new CaffeineDeviceRuntimeStateStore();
        Instant connectedAt = Instant.parse("2026-10-18T06:00:00Z");
        Instant heartbeatAt = connectedAt.plusSeconds(30);

        store.markOnline(DEVICE_ID, "session-1", connectedAt);
        store.refreshLastSeen(DEVICE_ID, "session-1", heartbeatAt);
        store.markOnline(DEVICE_ID, "session-2", heartbeatAt.plusSeconds(1));

        assertThat(store.markOffline(DEVICE_ID, "session-1", heartbeatAt.plusSeconds(2))).isEmpty();
        assertThat(store.find(DEVICE_ID).orElseThrow().onlineStatus()).isEqualTo(DeviceOnlineStatus.ONLINE);
        assertThat(store.markOffline(DEVICE_ID, "session-2", heartbeatAt.plusSeconds(3))).isPresent();
        assertThat(store.find(DEVICE_ID).orElseThrow().onlineStatus()).isEqualTo(DeviceOnlineStatus.OFFLINE);
    }

    @Test
    void storesConnectorStatusAndTypedChargingProgress() {
        CaffeineConnectorRuntimeStateStore store = new CaffeineConnectorRuntimeStateStore();
        Instant now = Instant.parse("2026-10-18T06:00:00Z");
        store.updateStatus(DEVICE_ID, 1, ConnectorStatus.PLUGGED, now);
        ChargingProgressEvent event = new ChargingProgressEvent(UUID.randomUUID(), DEVICE_ID, 1,
                "10001", new BigDecimal("750.5"), new BigDecimal("123.4"), 87,
                new BigDecimal("12.3456"), Duration.ofMinutes(125),
                new BigDecimal("7.8900"), now.plusSeconds(1));

        store.recordProgress(event);
        store.updateStatus(DEVICE_ID, 1, ConnectorStatus.CHARGING, event.occurredAt());

        assertThat(store.findConnector(DEVICE_ID, 1).orElseThrow().status())
                .isEqualTo(ConnectorStatus.CHARGING);
        assertThat(store.findProgress(DEVICE_ID, 1).orElseThrow()).satisfies(progress -> {
            assertThat(progress.transactionId()).isEqualTo("10001");
            assertThat(progress.energyKwh()).isEqualByComparingTo("12.3456");
            assertThat(progress.chargingDuration()).isEqualTo(Duration.ofMinutes(125));
        });
    }
}
