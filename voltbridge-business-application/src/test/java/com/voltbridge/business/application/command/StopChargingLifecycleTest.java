package com.voltbridge.business.application.command;

import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.transaction.ChargingTransactionResolver;
import com.voltbridge.business.application.transaction.HandleChargingEndedUseCase;
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
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.event.ChargingEndedEvent;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceCommandGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证停充命令与交易状态相互独立但可由真实结束事件关联完成。 */
class StopChargingLifecycleTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final String TRADE_NO = "12345678901234567890123456789012";

    @Test
    void reusesActiveStopAndCompletesItOnlyAfterEndCharge() {
        Fixture fixture = new Fixture(command -> CompletableFuture.completedFuture(null));

        UUID commandId = fixture.useCase.execute(DEVICE_CODE, "01");
        UUID duplicateId = fixture.useCase.execute(DEVICE_CODE, "1");

        assertThat(duplicateId).isEqualTo(commandId);
        assertThat(fixture.dispatchCount.get()).isEqualTo(1);
        assertThat(fixture.commands.findById(commandId).orElseThrow().status())
                .isEqualTo(DeviceCommandStatus.SENT);

        new HandleCommandResultUseCase(fixture.commands).handle(result(commandId,
                CommandResultStatus.ACKED, null, null));
        assertThat(fixture.commands.findById(commandId).orElseThrow().status())
                .isEqualTo(DeviceCommandStatus.ACKED);
        assertThat(fixture.transaction.status()).isEqualTo(ChargingTransactionStatus.CHARGING);

        ChargingTransactionResolver resolver = new ChargingTransactionResolver(
                fixture.devices, fixture.connectors, fixture.transactions);
        new HandleChargingEndedUseCase(resolver, fixture.transactions, fixture.commands)
                .handle(endEvent(fixture.time.now()));

        assertThat(fixture.transaction.status()).isEqualTo(ChargingTransactionStatus.ENDING);
        assertThat(fixture.commands.findById(commandId).orElseThrow().status())
                .isEqualTo(DeviceCommandStatus.SUCCEEDED);
    }

    @Test
    void rejectedStopFailsCommandButKeepsTransactionCharging() {
        Fixture fixture = new Fixture(command -> CompletableFuture.completedFuture(null));
        UUID commandId = fixture.useCase.execute(DEVICE_CODE, "01");

        new HandleCommandResultUseCase(fixture.commands).handle(result(commandId,
                CommandResultStatus.FAILED, "NOT_CHARGING", "Connector is not charging"));

        DeviceCommand failed = fixture.commands.findById(commandId).orElseThrow();
        assertThat(failed.status()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(failed.ackedAt()).isNotNull();
        assertThat(fixture.transaction.status()).isEqualTo(ChargingTransactionStatus.CHARGING);
    }

    @Test
    void timesOutStopWithoutChangingTransaction() {
        Fixture fixture = new Fixture(command -> CompletableFuture.completedFuture(null));
        UUID commandId = fixture.useCase.execute(DEVICE_CODE, "01");
        fixture.time.advance(Duration.ofSeconds(6));

        assertThat(new CommandTimeoutService(fixture.commands, fixture.time).checkExpiredCommands()).isEqualTo(1);
        assertThat(fixture.commands.findById(commandId).orElseThrow().status())
                .isEqualTo(DeviceCommandStatus.TIMEOUT);
        assertThat(fixture.transaction.status()).isEqualTo(ChargingTransactionStatus.CHARGING);
    }

    @Test
    void offlineStopPersistsExplicitFailureAndKeepsTransactionCharging() {
        Fixture fixture = new Fixture(command -> {
            throw new VoltBridgeException("DEVICE_OFFLINE", "Device session is unavailable");
        });

        assertThatThrownBy(() -> fixture.useCase.execute(DEVICE_CODE, "01"))
                .isInstanceOf(VoltBridgeException.class)
                .extracting("code").isEqualTo("DEVICE_OFFLINE");
        assertThat(fixture.commands.values()).singleElement().satisfies(command -> {
            assertThat(command.status()).isEqualTo(DeviceCommandStatus.FAILED);
            assertThat(command.failureCode()).isEqualTo("DEVICE_OFFLINE");
        });
        assertThat(fixture.transaction.status()).isEqualTo(ChargingTransactionStatus.CHARGING);
    }

    private static CommandResultEvent result(UUID commandId, CommandResultStatus status,
                                             String code, String reason) {
        return new CommandResultEvent(UUID.randomUUID(), new DeviceId(DEVICE_CODE), commandId,
                DEVICE_CODE + ":01", "01", "STOP_CHARGING", status, code, reason,
                status == CommandResultStatus.ACKED ? 1 : 0,
                status == CommandResultStatus.ACKED ? 0 : 2, false,
                Instant.parse("2026-10-18T06:00:01Z"));
    }

    private static ChargingEndedEvent endEvent(Instant now) {
        return new ChargingEndedEvent(UUID.randomUUID(), new DeviceId(DEVICE_CODE),
                DeviceProtocol.YUN_KUAI_CHONG_150, 1, TRADE_NO, 80,
                null, null, null, null, Duration.ofMinutes(10),
                new BigDecimal("5.5"), "12345678", "{}", now);
    }

    private static final class Fixture {
        private final MutableTimeProvider time = new MutableTimeProvider(
                Instant.parse("2026-10-18T06:00:00Z"));
        private final ChargingDevice device;
        private final Connector connector;
        private final FixedDeviceRepository devices;
        private final FixedConnectorRepository connectors;
        private final InMemoryCommandRepository commands = new InMemoryCommandRepository();
        private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
        private final ChargingTransaction transaction;
        private final AtomicInteger dispatchCount = new AtomicInteger();
        private final StopChargingUseCase useCase;

        private Fixture(DeviceCommandGateway gateway) {
            UUID tenantId = UUID.randomUUID();
            UUID deviceId = UUID.randomUUID();
            UUID stationId = UUID.randomUUID();
            device = new ChargingDevice(deviceId, tenantId, stationId, DEVICE_CODE, "Stop Test Device",
                    DeviceProtocol.YUN_KUAI_CHONG_150, "1.5", "ONLINE", time.now(), time.now(), 0);
            connector = new Connector(UUID.randomUUID(), tenantId, stationId, deviceId, 1,
                    "CONNECTOR-01", "DC", new BigDecimal("120"), "CHARGING", time.now(), time.now());
            devices = new FixedDeviceRepository(device);
            connectors = new FixedConnectorRepository(connector);
            transaction = ChargingTransaction.start(tenantId, deviceId, DEVICE_CODE, connector.id(), 1,
                    DeviceProtocol.YUN_KUAI_CHONG_150, UUID.randomUUID(), "ORDER-STOP", time.now());
            transaction.bindProtocolTransactionId(TRADE_NO, time.now());
            transaction.markCharging(time.now(), 40, time.now());
            transactions.save(transaction);
            DeviceCommandGateway countingGateway = command -> {
                dispatchCount.incrementAndGet();
                assertThat(command).isInstanceOf(StopChargingCommand.class);
                return gateway.dispatch(command);
            };
            useCase = new StopChargingUseCase(devices, connectors, transactions, commands,
                    countingGateway, time, Duration.ofSeconds(5));
        }
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
        private final List<DeviceCommand> values = new ArrayList<>();

        @Override
        public synchronized void save(DeviceCommand command) {
            values.removeIf(existing -> existing.id().equals(command.id()));
            values.add(command);
        }

        @Override
        public synchronized Optional<DeviceCommand> findById(UUID commandId) {
            return values.stream().filter(command -> command.id().equals(commandId)).findFirst();
        }

        @Override
        public synchronized List<DeviceCommand> findSentExpiredBefore(Instant now) {
            return values.stream().filter(command -> command.status() == DeviceCommandStatus.SENT)
                    .filter(command -> !command.expiredAt().isAfter(now)).toList();
        }

        @Override
        public synchronized Optional<DeviceCommand> findActiveByTypeAndBusinessRef(
                DeviceCommandType type, String businessRef) {
            return values.stream().filter(command -> command.type() == type)
                    .filter(command -> command.businessRef().equals(businessRef))
                    .filter(command -> command.status() == DeviceCommandStatus.CREATED
                            || command.status() == DeviceCommandStatus.SENT
                            || command.status() == DeviceCommandStatus.ACKED
                            || command.status() == DeviceCommandStatus.EXECUTING)
                    .findFirst();
        }

        @Override
        public synchronized DeviceCommand saveOrGetActive(DeviceCommand candidate) {
            return findActiveByTypeAndBusinessRef(candidate.type(), candidate.businessRef())
                    .orElseGet(() -> {
                        save(candidate);
                        return candidate;
                    });
        }

        private synchronized List<DeviceCommand> values() {
            return List.copyOf(values);
        }
    }

    private static final class InMemoryTransactionRepository implements ChargingTransactionRepository {
        private final List<ChargingTransaction> values = new ArrayList<>();
        @Override public void save(ChargingTransaction value) { values.removeIf(item -> item.id().equals(value.id())); values.add(value); }
        @Override public Optional<ChargingTransaction> findById(UUID id) { return values.stream().filter(item -> item.id().equals(id)).findFirst(); }
        @Override public Optional<ChargingTransaction> findByCommandId(UUID id) { return values.stream().filter(item -> id.equals(item.commandId())).findFirst(); }
        @Override public Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol, UUID deviceId, String tradeNo) { return values.stream().filter(item -> item.protocol() == protocol && item.deviceId().equals(deviceId) && tradeNo.equals(item.protocolTransactionId())).findFirst(); }
        @Override public Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId) { return values.stream().filter(ChargingTransaction::active).filter(item -> item.deviceId().equals(deviceId) && item.connectorId().equals(connectorId)).findFirst(); }
        @Override public List<ChargingTransaction> find(String deviceCode, Integer connectorNo, ChargingTransactionStatus status) { return values.stream().filter(item -> deviceCode == null || deviceCode.equals(item.deviceCode())).filter(item -> connectorNo == null || connectorNo == item.connectorNo()).filter(item -> status == null || status == item.status()).toList(); }
    }

    private record FixedDeviceRepository(ChargingDevice device) implements ChargingDeviceRepository {
        @Override public Optional<ChargingDevice> findById(UUID id) { return device.id().equals(id) ? Optional.of(device) : Optional.empty(); }
        @Override public Optional<ChargingDevice> findByDeviceCode(String code) { return device.deviceCode().equals(code) ? Optional.of(device) : Optional.empty(); }
        @Override public boolean updateStatus(String code, String status, Instant updatedAt) { return false; }
    }

    private record FixedConnectorRepository(Connector connector) implements ConnectorRepository {
        @Override public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) {
            return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo
                    ? Optional.of(connector) : Optional.empty();
        }
    }
}
