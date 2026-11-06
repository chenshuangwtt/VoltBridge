package com.voltbridge.business.application.charging;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandStatus;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.DeviceBusinessStatus;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.application.device.DeviceOperationPreconditions;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import com.voltbridge.device.runtime.DeviceRuntimeState;
import com.voltbridge.device.port.DeviceCommandGateway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class StartChargingFailurePersistenceTest {

    private static final String DEVICE_CODE = "VB-FAILURE-DEVICE";
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Test
    void longTransactionPersistenceFailureDoesNotMaskOriginalException() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        ChargingDevice device = new ChargingDevice(deviceId, tenantId, stationId, DEVICE_CODE,
                "Failure Test Device", DeviceProtocol.YUN_KUAI_CHONG_150, "1.5", DeviceBusinessStatus.ENABLED, NOW, NOW, 0);
        Connector connector = new Connector(UUID.randomUUID(), tenantId, stationId, deviceId, 1,
                "FAILURE-01", "DC", new BigDecimal("120"), "AVAILABLE", NOW, NOW);
        ChargingDeviceRepository deviceRepository = mock(ChargingDeviceRepository.class);
        ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
        DeviceCommandRepository commandRepository = mock(DeviceCommandRepository.class);
        ChargingTransactionRepository transactionRepository = mock(ChargingTransactionRepository.class);
        DeviceRuntimeStateStore deviceRuntimeStates = mock(DeviceRuntimeStateStore.class);
        ConnectorRuntimeStateStore connectorRuntimeStates = mock(ConnectorRuntimeStateStore.class);
        DeviceCommandGateway commandGateway = mock(DeviceCommandGateway.class);
        DataIntegrityViolationException original = new DataIntegrityViolationException("database failure: "
                + "cause=".repeat(120));

        when(deviceRepository.findByDeviceCode(DEVICE_CODE)).thenReturn(Optional.of(device));
        when(connectorRepository.findByDeviceIdAndConnectorNo(deviceId, 1)).thenReturn(Optional.of(connector));
        when(deviceRuntimeStates.find(new DeviceId(DEVICE_CODE))).thenReturn(Optional.of(
                new DeviceRuntimeState(new DeviceId(DEVICE_CODE), DeviceOnlineStatus.ONLINE,
                        "failure-test-session", NOW, NOW, null)));
        when(connectorRuntimeStates.findConnector(new DeviceId(DEVICE_CODE), 1)).thenReturn(Optional.empty());
        doThrow(original).when(transactionRepository).save(any(ChargingTransaction.class));
        StartChargingUseCase useCase = new StartChargingUseCase(deviceRepository, connectorRepository,
                commandRepository, transactionRepository,
                new DeviceOperationPreconditions(deviceRuntimeStates, connectorRuntimeStates),
                commandGateway, () -> NOW, Duration.ofSeconds(30));

        assertThatThrownBy(() -> useCase.execute(DEVICE_CODE, "01", new BigDecimal("10.00"),
                null, null, "ORDER-FAILURE"))
                .isSameAs(original);

        ArgumentCaptor<DeviceCommand> commandCaptor = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(commandRepository, times(2)).save(commandCaptor.capture());
        DeviceCommand failedCommand = commandCaptor.getAllValues().get(1);
        assertThat(failedCommand.status()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(failedCommand.failureCode()).isEqualTo("TRANSACTION_CREATE_FAILED");
        assertThat(failedCommand.failureReason()).isEqualTo("Unexpected internal error");
        assertThat(failedCommand.failureReason()).hasSizeLessThanOrEqualTo(512);
        verifyNoInteractions(commandGateway);
        verify(transactionRepository, never()).findByCommandId(any());
    }
}
