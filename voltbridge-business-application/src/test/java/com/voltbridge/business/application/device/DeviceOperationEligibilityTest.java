package com.voltbridge.business.application.device;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.device.DeviceBusinessStatus;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.model.DeviceOperationCapabilities;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.port.DeviceCommandGateway;
import com.voltbridge.device.port.DeviceOperationCapabilityProvider;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import com.voltbridge.device.command.SynchronizeTimeCommand;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import com.voltbridge.device.runtime.DeviceRuntimeState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

class DeviceOperationEligibilityTest {

    private static final String DEVICE_CODE = "ELIGIBILITY-DEVICE";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void offlineStartDoesNotCreateCommandOrTransaction() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.OFFLINE, ConnectorStatus.AVAILABLE);

        assertFailure(fixture.start(), "DEVICE_OFFLINE");

        verify(fixture.commandRepository, never()).save(any(DeviceCommand.class));
        verify(fixture.transactionRepository, never()).save(any(ChargingTransaction.class));
        verifyNoInteractions(fixture.commandGateway);
    }

    @Test
    void disabledDeviceCannotStart() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.DISABLED, DeviceOnlineStatus.ONLINE, ConnectorStatus.AVAILABLE);

        assertFailure(fixture.start(), "DEVICE_DISABLED");

        verify(fixture.commandRepository, never()).save(any(DeviceCommand.class));
        verify(fixture.transactionRepository, never()).save(any(ChargingTransaction.class));
    }

    @Test
    void enabledDeviceCanPassProtocolAdmission() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.UNKNOWN,
                ConnectorStatus.UNKNOWN);
        HandleDeviceConnectedUseCase admission = new HandleDeviceConnectedUseCase(
                fixture.deviceRepository, mock(DeviceRuntimeStateStore.class));

        var decision = admission.authorize(new DeviceConnectedEvent(UUID.randomUUID(),
                new DeviceId(DEVICE_CODE), DeviceProtocol.OCPP_16, "1.6J", "admission-session",
                "127.0.0.1", NOW));

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.reason()).isEqualTo("accepted");
    }

    @Test
    void disabledDeviceIsRejectedByProtocolAdmission() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.DISABLED, DeviceOnlineStatus.UNKNOWN,
                ConnectorStatus.UNKNOWN);
        HandleDeviceConnectedUseCase admission = new HandleDeviceConnectedUseCase(
                fixture.deviceRepository, mock(DeviceRuntimeStateStore.class));

        var decision = admission.authorize(new DeviceConnectedEvent(UUID.randomUUID(),
                new DeviceId(DEVICE_CODE), DeviceProtocol.OCPP_16, "1.6J", "admission-session",
                "127.0.0.1", NOW));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reason()).isEqualTo("device_disabled");
    }

    @Test
    void activeTransactionPreventsAnotherStart() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE, ConnectorStatus.CHARGING);
        ChargingTransaction active = ChargingTransaction.start(fixture.device.tenantId(), fixture.device.id(),
                DEVICE_CODE, fixture.connector.id(), 1, DeviceProtocol.OCPP_16,
                UUID.randomUUID(), "ACTIVE-ORDER", NOW);
        when(fixture.transactionRepository.findActiveByDeviceAndConnector(
                fixture.device.id(), fixture.connector.id())).thenReturn(Optional.of(active));

        assertFailure(fixture.start(), "ACTIVE_TRANSACTION_EXISTS");

        verify(fixture.commandRepository, never()).save(any(DeviceCommand.class));
        verify(fixture.transactionRepository, never()).save(any(ChargingTransaction.class));
    }

    @Test
    void onlineDeviceWithAvailableConnectorCanStart() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE, ConnectorStatus.AVAILABLE);

        UUID commandId = fixture.start().execute(DEVICE_CODE, "01", new BigDecimal("50.00"),
                null, null, "AVAILABLE-ORDER");

        assertThat(commandId).isNotNull();
        assertThat(fixture.device.businessStatus()).isEqualTo(DeviceBusinessStatus.ENABLED);
        verify(fixture.commandRepository).save(any(DeviceCommand.class));
        verify(fixture.transactionRepository).save(any(ChargingTransaction.class));
        verify(fixture.commandGateway).dispatch(any());
    }

    @Test
    void connectorFaultRuntimePreventsStartBeforePersistence() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE, ConnectorStatus.FAULT);

        assertFailure(fixture.start(), "CONNECTOR_NOT_AVAILABLE");

        verify(fixture.commandRepository, never()).save(any(DeviceCommand.class));
        verify(fixture.transactionRepository, never()).save(any(ChargingTransaction.class));
    }

    @Test
    void disabledOrFaultedBusinessConnectorPreventsStart() {
        for (String connectorStatus : new String[] {"DISABLED", "FAULTED"}) {
            Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE,
                    ConnectorStatus.AVAILABLE, connectorStatus);

            assertFailure(fixture.start(), "CONNECTOR_NOT_AVAILABLE");

            verify(fixture.commandRepository, never()).save(any(DeviceCommand.class));
            verify(fixture.transactionRepository, never()).save(any(ChargingTransaction.class));
        }
    }

    @Test
    void offlineTimeSyncAndResetAreRejectedBeforeCommandCreation() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.OFFLINE, ConnectorStatus.AVAILABLE);
        DeviceOperationCommandUseCase operations = fixture.operations();

        assertThatThrownBy(() -> operations.synchronizeTime(DEVICE_CODE, NOW))
                .isInstanceOf(VoltBridgeException.class).extracting("code").isEqualTo("DEVICE_OFFLINE");
        assertThatThrownBy(() -> operations.reset(DEVICE_CODE, false))
                .isInstanceOf(VoltBridgeException.class).extracting("code").isEqualTo("DEVICE_OFFLINE");

        verifyNoInteractions(fixture.commandRepository, fixture.commandGateway);
    }

    @Test
    void disabledTimeSyncAndResetAreRejectedBeforeCommandCreation() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.DISABLED, DeviceOnlineStatus.ONLINE, ConnectorStatus.AVAILABLE);
        DeviceOperationCommandUseCase operations = fixture.operations();

        assertThatThrownBy(() -> operations.synchronizeTime(DEVICE_CODE, NOW))
                .isInstanceOf(VoltBridgeException.class).extracting("code").isEqualTo("DEVICE_DISABLED");
        assertThatThrownBy(() -> operations.reset(DEVICE_CODE, false))
                .isInstanceOf(VoltBridgeException.class).extracting("code").isEqualTo("DEVICE_DISABLED");

        verifyNoInteractions(fixture.commandRepository, fixture.commandGateway);
    }

    @Test
    void timeSyncWithoutTargetUsesServerCurrentTime() {
        Fixture fixture = new Fixture(DeviceBusinessStatus.ENABLED, DeviceOnlineStatus.ONLINE, ConnectorStatus.AVAILABLE);
        when(fixture.commandRepository.saveOrGetActive(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.commandGateway.dispatch(any())).thenReturn(new CompletableFuture<>());

        fixture.operations().synchronizeTime(DEVICE_CODE, null);

        var commandCaptor = forClass(com.voltbridge.device.command.DeviceCommand.class);
        verify(fixture.commandGateway).dispatch(commandCaptor.capture());
        assertThat(commandCaptor.getValue()).isInstanceOf(SynchronizeTimeCommand.class);
        assertThat(((SynchronizeTimeCommand) commandCaptor.getValue()).targetTime()).isEqualTo(NOW);
    }

    private void assertFailure(StartChargingUseCase useCase, String expectedCode) {
        assertThatThrownBy(() -> useCase.execute(DEVICE_CODE, "01", new BigDecimal("50.00"),
                null, null, "REJECTED-ORDER"))
                .isInstanceOf(VoltBridgeException.class)
                .extracting("code").isEqualTo(expectedCode);
    }

    private static final class Fixture {
        private final ChargingDevice device;
        private final Connector connector;
        private final ChargingDeviceRepository deviceRepository = mock(ChargingDeviceRepository.class);
        private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
        private final DeviceCommandRepository commandRepository = mock(DeviceCommandRepository.class);
        private final ChargingTransactionRepository transactionRepository = mock(ChargingTransactionRepository.class);
        private final DeviceCommandGateway commandGateway = mock(DeviceCommandGateway.class);
        private final DeviceOperationPreconditions preconditions;

        private Fixture(DeviceBusinessStatus businessStatus, DeviceOnlineStatus onlineStatus, ConnectorStatus connectorStatus) {
            this(businessStatus, onlineStatus, connectorStatus, "AVAILABLE");
        }

        private Fixture(DeviceBusinessStatus businessStatus, DeviceOnlineStatus onlineStatus, ConnectorStatus connectorStatus,
                        String connectorBusinessStatus) {
            UUID deviceId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            UUID stationId = UUID.randomUUID();
            device = new ChargingDevice(deviceId, tenantId, stationId, DEVICE_CODE, "Eligibility test device",
                    DeviceProtocol.OCPP_16, "1.6J", businessStatus, NOW, NOW, 0);
            connector = new Connector(UUID.randomUUID(), tenantId, stationId, deviceId, 1,
                    "ELIGIBILITY-01", "DC", new BigDecimal("120"), connectorBusinessStatus, NOW, NOW);

            DeviceRuntimeStateStore deviceRuntimeStates = mock(DeviceRuntimeStateStore.class);
            when(deviceRuntimeStates.find(new DeviceId(DEVICE_CODE))).thenReturn(Optional.of(
                    new DeviceRuntimeState(new DeviceId(DEVICE_CODE), onlineStatus,
                            "eligibility-session", NOW, NOW, onlineStatus == DeviceOnlineStatus.OFFLINE ? NOW : null)));
            ConnectorRuntimeStateStore connectorRuntimeStates = mock(ConnectorRuntimeStateStore.class);
            when(connectorRuntimeStates.findConnector(new DeviceId(DEVICE_CODE), 1)).thenReturn(Optional.of(
                    new ConnectorRuntimeState(new DeviceId(DEVICE_CODE), 1, connectorStatus, NOW, NOW)));
            preconditions = new DeviceOperationPreconditions(deviceRuntimeStates, connectorRuntimeStates);

            when(deviceRepository.findByDeviceCode(DEVICE_CODE)).thenReturn(Optional.of(device));
            when(connectorRepository.findByDeviceIdAndConnectorNo(deviceId, 1)).thenReturn(Optional.of(connector));
            when(commandGateway.dispatch(any())).thenReturn(CompletableFuture.completedFuture(null));
        }

        private StartChargingUseCase start() {
            return new StartChargingUseCase(deviceRepository, connectorRepository, commandRepository,
                    transactionRepository, preconditions, commandGateway, () -> NOW, Duration.ofSeconds(30));
        }

        private DeviceOperationCommandUseCase operations() {
            return new DeviceOperationCommandUseCase(deviceRepository, preconditions,
                    new FindDeviceOperationCapabilitiesUseCase(List.of(new DeviceOperationCapabilityProvider() {
                        @Override
                        public DeviceProtocol protocol() {
                            return device.protocol();
                        }

                        @Override
                        public DeviceOperationCapabilities operationCapabilities() {
                            return new DeviceOperationCapabilities(true, true, true, true);
                        }
                    })), commandRepository,
                    commandGateway, () -> NOW, Duration.ofSeconds(30));
        }
    }
}
