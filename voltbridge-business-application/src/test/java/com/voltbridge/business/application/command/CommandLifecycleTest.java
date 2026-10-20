package com.voltbridge.business.application.command;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandStatus;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.port.DeviceCommandGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandLifecycleTest {

    private static final String DEVICE_CODE = "20231212000010";

    @Test
    void marksSentCommandTimedOutWithoutSleeping() {
        MutableTimeProvider time = new MutableTimeProvider(Instant.parse("2026-10-18T03:00:00Z"));
        InMemoryCommandRepository commands = new InMemoryCommandRepository();
        DeviceCommand command = DeviceCommand.create(UUID.randomUUID(), UUID.randomUUID(), DEVICE_CODE,
                UUID.randomUUID(), "01", DeviceCommandType.START_CHARGING, "ORDER-1", "{}",
                time.now(), time.now().plusSeconds(5));
        command.markSent(time.now());
        commands.save(command);
        time.advance(Duration.ofSeconds(6));

        int count = new CommandTimeoutService(commands, time).checkExpiredCommands();

        assertThat(count).isEqualTo(1);
        assertThat(command.status()).isEqualTo(DeviceCommandStatus.TIMEOUT);
        assertThat(command.completedAt()).isEqualTo(time.now());
    }

    @Test
    void persistsExplicitFailureWhenRegisteredDeviceIsOffline() {
        MutableTimeProvider time = new MutableTimeProvider(Instant.parse("2026-10-18T03:00:00Z"));
        UUID tenantId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        ChargingDevice device = new ChargingDevice(deviceId, tenantId, stationId, DEVICE_CODE,
                "Offline device", DeviceProtocol.YUN_KUAI_CHONG_150, "1.5", "REGISTERED",
                time.now(), time.now(), 0);
        Connector connector = new Connector(connectorId, tenantId, stationId, deviceId, 1,
                "CONNECTOR-01", "DC", new BigDecimal("120"), "AVAILABLE", time.now(), time.now());
        InMemoryCommandRepository commands = new InMemoryCommandRepository();
        InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
        DeviceCommandGateway offlineGateway = command -> {
            throw new VoltBridgeException("DEVICE_OFFLINE", "Device is not connected: " + DEVICE_CODE);
        };
        StartChargingUseCase useCase = new StartChargingUseCase(
                new FixedDeviceRepository(device), new FixedConnectorRepository(connector), commands, transactions,
                offlineGateway, time, Duration.ofSeconds(30));

        assertThatThrownBy(() -> useCase.execute(DEVICE_CODE, "01", new BigDecimal("50"),
                "1234567890123456", "1234567890123456", "ORDER-2"))
                .isInstanceOf(VoltBridgeException.class)
                .extracting("code").isEqualTo("DEVICE_OFFLINE");
        assertThat(commands.values()).singleElement().satisfies(command -> {
            assertThat(command.status()).isEqualTo(DeviceCommandStatus.FAILED);
            assertThat(command.failureCode()).isEqualTo("DEVICE_OFFLINE");
        });
        assertThat(transactions.values()).singleElement()
                .extracting(ChargingTransaction::status).isEqualTo(ChargingTransactionStatus.START_FAILED);
    }

    @Test
    void ignoresConflictingFailureAfterCommandWasAcknowledged() {
        MutableTimeProvider time = new MutableTimeProvider(Instant.parse("2026-10-18T03:00:00Z"));
        UUID deviceId = UUID.randomUUID();
        InMemoryCommandRepository commands = new InMemoryCommandRepository();
        DeviceCommand command = DeviceCommand.create(UUID.randomUUID(), deviceId, DEVICE_CODE,
                UUID.randomUUID(), "01", DeviceCommandType.START_CHARGING, "ORDER-3", "{}",
                time.now(), time.now().plusSeconds(30));
        command.markSent(time.now());
        command.markAcked("{}", time.now());
        commands.save(command);
        HandleCommandResultUseCase handler = new HandleCommandResultUseCase(commands);

        handler.handle(new CommandResultEvent(UUID.randomUUID(), new DeviceId(DEVICE_CODE), command.id(),
                "12345678901234567890123456789012", "01", "START_CHARGING",
                CommandResultStatus.FAILED, "DEVICE_FAULT", "Device fault", true, time.now()));

        assertThat(command.status()).isEqualTo(DeviceCommandStatus.ACKED);
        assertThat(command.failureCode()).isNull();
    }

    private static final class MutableTimeProvider implements TimeProvider {
        private Instant now;

        private MutableTimeProvider(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class InMemoryCommandRepository implements DeviceCommandRepository {
        private final List<DeviceCommand> commands = new ArrayList<>();

        @Override
        public void save(DeviceCommand command) {
            commands.removeIf(existing -> existing.id().equals(command.id()));
            commands.add(command);
        }

        @Override
        public Optional<DeviceCommand> findById(UUID commandId) {
            return commands.stream().filter(command -> command.id().equals(commandId)).findFirst();
        }

        @Override
        public List<DeviceCommand> findSentExpiredBefore(Instant now) {
            return commands.stream()
                    .filter(command -> command.status() == DeviceCommandStatus.SENT)
                    .filter(command -> !command.expiredAt().isAfter(now))
                    .toList();
        }

        private List<DeviceCommand> values() {
            return List.copyOf(commands);
        }
    }

    private static final class InMemoryTransactionRepository implements ChargingTransactionRepository {
        private final List<ChargingTransaction> transactions = new ArrayList<>();

        @Override public void save(ChargingTransaction transaction) { transactions.removeIf(item -> item.id().equals(transaction.id())); transactions.add(transaction); }
        @Override public Optional<ChargingTransaction> findById(UUID id) { return transactions.stream().filter(item -> item.id().equals(id)).findFirst(); }
        @Override public Optional<ChargingTransaction> findByCommandId(UUID id) { return transactions.stream().filter(item -> id.equals(item.commandId())).findFirst(); }
        @Override public Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol, UUID deviceId, String tradeNo) { return transactions.stream().filter(item -> item.protocol() == protocol && item.deviceId().equals(deviceId) && tradeNo.equals(item.protocolTransactionId())).findFirst(); }
        @Override public Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId) { return transactions.stream().filter(ChargingTransaction::active).filter(item -> item.deviceId().equals(deviceId) && item.connectorId().equals(connectorId)).findFirst(); }
        @Override public List<ChargingTransaction> find(String deviceCode, Integer connectorNo, ChargingTransactionStatus status) { return transactions.stream().filter(item -> deviceCode == null || deviceCode.equals(item.deviceCode())).filter(item -> connectorNo == null || connectorNo == item.connectorNo()).filter(item -> status == null || status == item.status()).toList(); }
        List<ChargingTransaction> values() { return List.copyOf(transactions); }
    }

    private record FixedDeviceRepository(ChargingDevice device) implements ChargingDeviceRepository {
        @Override
        public Optional<ChargingDevice> findById(UUID id) {
            return device.id().equals(id) ? Optional.of(device) : Optional.empty();
        }

        @Override
        public Optional<ChargingDevice> findByDeviceCode(String deviceCode) {
            return device.deviceCode().equals(deviceCode) ? Optional.of(device) : Optional.empty();
        }

        @Override
        public boolean updateStatus(String deviceCode, String status, Instant updatedAt) {
            return false;
        }
    }

    private record FixedConnectorRepository(Connector connector) implements ConnectorRepository {
        @Override
        public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) {
            return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo
                    ? Optional.of(connector) : Optional.empty();
        }
    }
}
