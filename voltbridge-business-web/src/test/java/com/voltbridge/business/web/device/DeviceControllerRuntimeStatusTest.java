package com.voltbridge.business.web.device;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.device.DeviceOperationCommandUseCase;
import com.voltbridge.business.application.device.DeviceOperationPreconditions;
import com.voltbridge.business.application.device.FindDeviceOperationCapabilitiesUseCase;
import com.voltbridge.business.application.device.FindChargingDeviceUseCase;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.DeviceBusinessStatus;
import com.voltbridge.common.result.ApiResponse;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.model.DeviceOperationCapabilities;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import com.voltbridge.device.port.DeviceOperationCapabilityProvider;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import com.voltbridge.device.runtime.DeviceRuntimeState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceControllerRuntimeStatusTest {

    private static final String DEVICE_CODE = "API-DEVICE-001";
    private static final Instant LAST_SEEN = Instant.parse("2026-11-05T10:15:30Z");

    @Test
    void reportsEnabledBusinessStatusSeparatelyFromOnlineRuntimeStatus() {
        DeviceController.DeviceView view = find(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE);

        assertThat(view.businessStatus()).isEqualTo(DeviceBusinessStatus.ENABLED);
        assertThat(view.onlineStatus()).isEqualTo(DeviceOnlineStatus.ONLINE);
        assertThat(view.lastSeenAt()).isEqualTo(LAST_SEEN);
    }

    @Test
    void reportsEnabledBusinessStatusSeparatelyFromOfflineRuntimeStatus() {
        DeviceController.DeviceView view = find(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.OFFLINE);

        assertThat(view.businessStatus()).isEqualTo(DeviceBusinessStatus.ENABLED);
        assertThat(view.onlineStatus()).isEqualTo(DeviceOnlineStatus.OFFLINE);
        assertThat(view.lastSeenAt()).isEqualTo(LAST_SEEN);
    }

    @Test
    void exposesOperationCapabilitiesFromTheDeviceProtocol() {
        DeviceController.DeviceView view = find(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE,
                DeviceProtocol.YUN_KUAI_CHONG_150,
                new DeviceOperationCapabilities(true, true, true, true));

        assertThat(view.capabilities()).isEqualTo(
                new DeviceController.DeviceOperationCapabilitiesView(true, true, true, true));
    }

    private DeviceController.DeviceView find(DeviceBusinessStatus businessStatus, DeviceOnlineStatus runtimeStatus) {
        return find(businessStatus, runtimeStatus, DeviceProtocol.OCPP_16, DeviceOperationCapabilities.NONE);
    }

    private DeviceController.DeviceView find(DeviceBusinessStatus businessStatus, DeviceOnlineStatus runtimeStatus,
                                             DeviceProtocol protocol,
                                             DeviceOperationCapabilities capabilities) {
        UUID deviceId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant createdAt = LAST_SEEN.minusSeconds(60);
        ChargingDevice device = new ChargingDevice(deviceId, tenantId, UUID.randomUUID(), DEVICE_CODE,
                "API test device", protocol, protocol == DeviceProtocol.OCPP_16 ? "1.6J" : "1.5",
                businessStatus, createdAt, createdAt, 0);
        FindChargingDeviceUseCase findDevice = mock(FindChargingDeviceUseCase.class);
        when(findDevice.execute(DEVICE_CODE)).thenReturn(device);

        DeviceRuntimeStateStore deviceRuntimeStates = mock(DeviceRuntimeStateStore.class);
        when(deviceRuntimeStates.find(new DeviceId(DEVICE_CODE))).thenReturn(Optional.of(
                new DeviceRuntimeState(new DeviceId(DEVICE_CODE), runtimeStatus, "api-test-session",
                        createdAt, LAST_SEEN, null)));
        ConnectorRuntimeStateStore connectorRuntimeStates = mock(ConnectorRuntimeStateStore.class);
        when(connectorRuntimeStates.findConnectors(new DeviceId(DEVICE_CODE))).thenReturn(List.of(
                new ConnectorRuntimeState(new DeviceId(DEVICE_CODE), 1, ConnectorStatus.AVAILABLE,
                        LAST_SEEN.minusSeconds(30), LAST_SEEN)));

        DeviceOperationCapabilityProvider capabilityProvider = mock(DeviceOperationCapabilityProvider.class);
        when(capabilityProvider.protocol()).thenReturn(protocol);
        when(capabilityProvider.operationCapabilities()).thenReturn(capabilities);
        DeviceController controller = new DeviceController(findDevice, mock(StartChargingUseCase.class),
                mock(StopChargingUseCase.class), mock(DeviceOperationCommandUseCase.class),
                new DeviceOperationPreconditions(deviceRuntimeStates, connectorRuntimeStates),
                new FindDeviceOperationCapabilitiesUseCase(List.of(capabilityProvider)));
        ApiResponse<DeviceController.DeviceView> response = controller.find(DEVICE_CODE);

        assertThat(response.success()).isTrue();
        assertThat(response.data().connectors()).singleElement()
                .satisfies(connector -> assertThat(connector.runtimeStatus()).isEqualTo(ConnectorStatus.AVAILABLE));
        return response.data();
    }
}
