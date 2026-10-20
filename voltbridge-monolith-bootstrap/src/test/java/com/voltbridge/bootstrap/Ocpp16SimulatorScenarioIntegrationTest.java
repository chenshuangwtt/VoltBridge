package com.voltbridge.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.command.HandleCommandResultUseCase;
import com.voltbridge.business.application.device.FindChargingDeviceUseCase;
import com.voltbridge.business.application.device.HandleChargingProgressUseCase;
import com.voltbridge.business.application.device.HandleConnectorStatusUseCase;
import com.voltbridge.business.application.device.HandleDeviceConnectedUseCase;
import com.voltbridge.business.application.device.HandleDeviceDisconnectedUseCase;
import com.voltbridge.business.application.device.HandleHeartbeatUseCase;
import com.voltbridge.business.application.transaction.ChargingStartedService;
import com.voltbridge.business.application.transaction.ChargingTransactionResolver;
import com.voltbridge.business.application.transaction.HandleTransactionRecordUseCase;
import com.voltbridge.business.application.transaction.StartDeviceTransactionUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandStatus;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.web.device.DeviceController;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.gateway.command.DeviceCommandDispatcher;
import com.voltbridge.gateway.event.DeviceEventDispatcher;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.routing.DeviceRouter;
import com.voltbridge.gateway.session.LocalDeviceSessionRegistry;
import com.voltbridge.gateway.session.LocalGatewayConnectionCoordinator;
import com.voltbridge.infra.cache.CaffeineConnectorRuntimeStateStore;
import com.voltbridge.infra.cache.CaffeineDeviceRouteDirectory;
import com.voltbridge.infra.cache.CaffeineDeviceRuntimeStateStore;
import com.voltbridge.infra.messaging.memory.InMemoryDeviceEventPublisher;
import com.voltbridge.protocol.ocpp16.Ocpp16Properties;
import com.voltbridge.protocol.ocpp16.Ocpp16ProtocolAdapter;
import com.voltbridge.protocol.ocpp16.action.BootNotificationHandler;
import com.voltbridge.protocol.ocpp16.action.HeartbeatHandler;
import com.voltbridge.protocol.ocpp16.action.MeterValuesHandler;
import com.voltbridge.protocol.ocpp16.action.Ocpp16ActionHandler;
import com.voltbridge.protocol.ocpp16.action.Ocpp16ActionHandlerRegistry;
import com.voltbridge.protocol.ocpp16.action.StartTransactionHandler;
import com.voltbridge.protocol.ocpp16.action.StatusNotificationHandler;
import com.voltbridge.protocol.ocpp16.action.StopTransactionHandler;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.correlation.Ocpp16PendingRequestRegistry;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.ocpp16.Ocpp16Simulator;
import com.voltbridge.testing.simulator.scenario.ChargingScenarioConfig;
import com.voltbridge.testing.simulator.scenario.ChargingScenarioRunner;
import com.voltbridge.testing.simulator.scenario.DeterministicChargingProfile;
import com.voltbridge.testing.simulator.scenario.PlatformScenarioControl;
import com.voltbridge.testing.simulator.scenario.ScenarioResult;
import com.voltbridge.transport.websocket.NettyWebSocketServer;
import com.voltbridge.transport.websocket.WebSocketServerProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用外部 WebSocket Charge Point 模拟器验证 OCPP 1.6J 完整充电生命周期。 */
class Ocpp16SimulatorScenarioIntegrationTest {

    private static final String DEVICE_CODE = "OCPP-SIM-001";

    @Test
    void remoteStartThenTargetCompletePersistsRepeatedMeterValues() throws Exception {
        ScenarioFixture fixture = ScenarioFixture.start();
        ChargingScenarioConfig config = config("ocpp-remote-start-target-complete", CompletionMode.NATURAL_TARGET);
        try (Ocpp16Simulator simulator = fixture.simulator()) {
            ScenarioResult result = new ChargingScenarioRunner().runRemoteStartScenario(simulator, config, fixture.control());
            ChargingTransaction transaction = fixture.transactions.findById(result.chargingTransactionId()).orElseThrow();
            assertThat(result.samplesSent()).isGreaterThan(2);
            assertThat(transaction.status()).isEqualTo(ChargingTransactionStatus.COMPLETED);
            assertThat(transaction.endMeterKwh()).isEqualByComparingTo(config.initialMeterKwh().add(config.targetEnergyKwh()));
            assertThat(fixture.commands.findById(result.startCommandId()).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SUCCEEDED);
            assertThat(fixture.connectorStates.findProgress(new com.voltbridge.device.model.DeviceId(DEVICE_CODE), 1)).isPresent();
            BillingRecord bill = fixture.billing.requireByTransactionId(transaction.id());
            assertThat(bill.totalAmount()).isEqualByComparingTo("5.00");
            assertThat(bill.lineItems()).hasSize(2);
        } finally { fixture.close(); }
    }

    @Test
    void remoteStartThenRemoteStopWaitsForStopTransactionBeforeCompletion() throws Exception {
        ScenarioFixture fixture = ScenarioFixture.start();
        try (Ocpp16Simulator simulator = fixture.simulator()) {
            ScenarioResult result = new ChargingScenarioRunner().runRemoteStartScenario(simulator,
                    config("ocpp-remote-start-remote-stop", CompletionMode.REMOTE_STOP), fixture.control());
            assertThat(fixture.commands.findById(result.stopCommandId()).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SUCCEEDED);
            assertThat(fixture.transactions.findById(result.chargingTransactionId()).orElseThrow().status())
                    .isEqualTo(ChargingTransactionStatus.COMPLETED);
            assertThat(result.trace()).anyMatch(item -> item.contains("RemoteStopTransaction accepted"));
            assertThat(result.trace()).anyMatch(item -> item.contains("StopTransaction finalized"));
        } finally { fixture.close(); }
    }

    @Test
    void localStartCompletesWithoutCreatingPlatformDeviceCommand() throws Exception {
        ScenarioFixture fixture = ScenarioFixture.start();
        ChargingScenarioConfig config = config("ocpp-local-start-target-complete", CompletionMode.NATURAL_TARGET);
        try (Ocpp16Simulator simulator = fixture.simulator()) {
            List<com.voltbridge.testing.simulator.core.ChargingSample> samples = new DeterministicChargingProfile(config).samples();
            simulator.connect(); simulator.loginOrBoot(); simulator.heartbeat(); simulator.connectorReady();
            simulator.beginLocalCharging(samples.getFirst(), "LOCAL-SIM-CARD");
            for (int index = 1; index < samples.size(); index++) simulator.sendChargingProgress(samples.get(index));
            simulator.completeCharging(samples.getLast(), CompletionMode.NATURAL_TARGET, false);
            await(() -> fixture.transactions.find(DEVICE_CODE, 1, ChargingTransactionStatus.COMPLETED).size() == 1);
            ChargingTransaction transaction = fixture.transactions.find(DEVICE_CODE, 1, ChargingTransactionStatus.COMPLETED).getFirst();
            assertThat(transaction.commandId()).isNull();
            assertThat(fixture.commands.size()).isZero();
        } finally { fixture.close(); }
    }

    private static ChargingScenarioConfig config(String name, CompletionMode mode) {
        return new ChargingScenarioConfig(name, DEVICE_CODE, 1, Duration.ofMillis(300), Duration.ofMinutes(30),
                Duration.ofMillis(50), 10, 75, new BigDecimal("0.0000"), new BigDecimal("5.0000"),
                new BigDecimal("2000.0000"), new BigDecimal("7.2"), mode,
                Instant.parse("2026-10-19T09:00:00Z"), false);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        assertThat(condition.getAsBoolean()).as("condition should become true before timeout").isTrue();
    }

    private static final class ScenarioFixture implements AutoCloseable {
        private final NettyWebSocketServer server; private final DeviceController controller;
        private final InMemoryCommandRepository commands; private final InMemoryChargingTransactionRepository transactions;
        private final CaffeineConnectorRuntimeStateStore connectorStates; private final BillingScenarioSupport billing; private final Control control;
        private ScenarioFixture(NettyWebSocketServer server, DeviceController controller, InMemoryCommandRepository commands,
                                InMemoryChargingTransactionRepository transactions, CaffeineConnectorRuntimeStateStore connectorStates,
                                BillingScenarioSupport billing) {
            this.server = server; this.controller = controller; this.commands = commands; this.transactions = transactions;
            this.connectorStates = connectorStates; this.billing = billing; this.control = new Control(controller, commands, transactions);
        }
        static ScenarioFixture start() {
            TimeProvider clock = Instant::now;
            InMemoryDeviceRepository devices = new InMemoryDeviceRepository();
            InMemoryConnectorRepository connectors = new InMemoryConnectorRepository(devices.device());
            InMemoryCommandRepository commands = new InMemoryCommandRepository();
            InMemoryChargingTransactionRepository transactions = new InMemoryChargingTransactionRepository();
            BillingScenarioSupport billing = new BillingScenarioSupport(transactions, devices.device().tenantId());
            CaffeineDeviceRuntimeStateStore deviceStates = new CaffeineDeviceRuntimeStateStore();
            CaffeineConnectorRuntimeStateStore connectorStates = new CaffeineConnectorRuntimeStateStore();
            HandleDeviceConnectedUseCase connected = new HandleDeviceConnectedUseCase(devices, deviceStates);
            ChargingTransactionResolver resolver = new ChargingTransactionResolver(devices, connectors, transactions);
            ChargingStartedService started = new ChargingStartedService(transactions, commands);
            StartDeviceTransactionUseCase transactionStart = new StartDeviceTransactionUseCase(devices, connectors, transactions,
                    new AtomicLong(1000)::incrementAndGet, started);
            DeviceEventDispatcher dispatcher = new DeviceEventDispatcher(new InMemoryDeviceEventPublisher(List.of(connected,
                    new HandleDeviceDisconnectedUseCase(deviceStates, DeviceRuntimeMetrics.NOOP),
                    new HandleHeartbeatUseCase(deviceStates, DeviceRuntimeMetrics.NOOP),
                    new HandleConnectorStatusUseCase(connectorStates, DeviceRuntimeMetrics.NOOP),
                    new HandleChargingProgressUseCase(connectorStates, DeviceRuntimeMetrics.NOOP),
                    new HandleCommandResultUseCase(commands), new HandleTransactionRecordUseCase(resolver, transactions, commands, billing::onBusinessEvent))));
            Ocpp16Properties properties = new Ocpp16Properties();
            properties.setHeartbeatInterval(Duration.ofSeconds(30)); properties.setRequestTimeout(Duration.ofMinutes(1));
            Ocpp16FrameCodec codec = new Ocpp16FrameCodec(new ObjectMapper());
            List<Ocpp16ActionHandler> actionHandlers = List.of(new BootNotificationHandler(codec, clock, properties),
                    new HeartbeatHandler(codec, clock), new StatusNotificationHandler(codec, clock),
                    new StartTransactionHandler(codec, transactionStart, clock), new MeterValuesHandler(codec, clock),
                    new StopTransactionHandler(codec, clock));
            Ocpp16ProtocolAdapter adapter = new Ocpp16ProtocolAdapter(codec, new Ocpp16ActionHandlerRegistry(actionHandlers),
                    new Ocpp16PendingRequestRegistry(clock, properties), clock);
            LocalDeviceSessionRegistry sessions = new LocalDeviceSessionRegistry();
            CaffeineDeviceRouteDirectory routes = new CaffeineDeviceRouteDirectory();
            ProtocolRegistry registry = new ProtocolRegistry(List.of(adapter));
            LocalGatewayConnectionCoordinator coordinator = new LocalGatewayConnectionCoordinator(sessions, routes, registry,
                    connected, dispatcher, clock, "scenario-ocpp", Duration.ofMinutes(10));
            DeviceCommandDispatcher gateway = new DeviceCommandDispatcher(new DeviceRouter(routes, clock), sessions, registry);
            DeviceController controller = new DeviceController(new FindChargingDeviceUseCase(devices),
                    new StartChargingUseCase(devices, connectors, commands, transactions, gateway, clock, Duration.ofSeconds(5)),
                    new StopChargingUseCase(devices, connectors, transactions, commands, gateway, clock, Duration.ofSeconds(5)));
            WebSocketServerProperties serverProperties = new WebSocketServerProperties();
            serverProperties.setHost("127.0.0.1"); serverProperties.setPort(0);
            NettyWebSocketServer server = new NettyWebSocketServer(serverProperties, List.of(coordinator)); server.start();
            return new ScenarioFixture(server, controller, commands, transactions, connectorStates, billing);
        }
        Ocpp16Simulator simulator() { return new Ocpp16Simulator("127.0.0.1", server.boundPort(), "/ocpp", DEVICE_CODE, 1); }
        PlatformScenarioControl control() { return control; }
        @Override public void close() { server.stop(); }
    }

    private static final class Control implements PlatformScenarioControl {
        private final DeviceController controller; private final InMemoryCommandRepository commands; private final InMemoryChargingTransactionRepository transactions; private UUID startId;
        private Control(DeviceController controller, InMemoryCommandRepository commands, InMemoryChargingTransactionRepository transactions) { this.controller = controller; this.commands = commands; this.transactions = transactions; }
        @Override public UUID requestRemoteStart() { startId = controller.start(DEVICE_CODE, new DeviceController.StartChargingRequest("1", new BigDecimal("50.00"), "1234567890123456", "1234567890123456", "SIM-OCPP-START")).data().commandId(); return startId; }
        @Override public UUID requestRemoteStop() { return controller.stop(DEVICE_CODE, new DeviceController.StopChargingRequest("1")).data().commandId(); }
        @Override public void awaitStartAcknowledged(UUID id) throws Exception { awaitCommand(id, DeviceCommandStatus.ACKED); }
        @Override public void awaitStartSucceeded(UUID id) throws Exception { awaitCommand(id, DeviceCommandStatus.SUCCEEDED); }
        @Override public void awaitTransactionCharging() throws Exception { awaitTransaction(ChargingTransactionStatus.CHARGING); }
        @Override public void awaitStopAcknowledged(UUID id) throws Exception { awaitCommand(id, DeviceCommandStatus.ACKED); }
        @Override public void awaitStopSucceeded(UUID id) throws Exception { awaitCommand(id, DeviceCommandStatus.SUCCEEDED); }
        @Override public void awaitTransactionCompleted() throws Exception { awaitTransaction(ChargingTransactionStatus.COMPLETED); }
        @Override public UUID chargingTransactionId() { return transactions.findByCommandId(startId).orElseThrow().id(); }
        private void awaitCommand(UUID id, DeviceCommandStatus status) throws Exception { await(() -> commands.findById(id).map(command -> command.status() == status).orElse(false)); }
        private void awaitTransaction(ChargingTransactionStatus status) throws Exception { await(() -> transactions.findByCommandId(startId).map(tx -> tx.status() == status).orElse(false)); }
    }

    private static final class InMemoryDeviceRepository implements ChargingDeviceRepository {
        private final UUID id = UUID.randomUUID(), tenantId = UUID.randomUUID(), stationId = UUID.randomUUID();
        ChargingDevice device() { return new ChargingDevice(id, tenantId, stationId, DEVICE_CODE, "OCPP Simulator Device", DeviceProtocol.OCPP_16, "1.6J", "REGISTERED", Instant.now(), Instant.now(), 0); }
        @Override public Optional<ChargingDevice> findById(UUID value) { return id.equals(value) ? Optional.of(device()) : Optional.empty(); }
        @Override public Optional<ChargingDevice> findByDeviceCode(String code) { return DEVICE_CODE.equals(code) ? Optional.of(device()) : Optional.empty(); }
        @Override public boolean updateStatus(String code, String status, Instant updatedAt) { return DEVICE_CODE.equals(code); }
    }
    private static final class InMemoryConnectorRepository implements ConnectorRepository {
        private final Connector connector;
        private InMemoryConnectorRepository(ChargingDevice device) { connector = new Connector(UUID.randomUUID(), device.tenantId(), device.stationId(), device.id(), 1, "SIM-OCPP-01", "DC", new BigDecimal("120"), "AVAILABLE", Instant.now(), Instant.now()); }
        @Override public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) { return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo ? Optional.of(connector) : Optional.empty(); }
    }
    private static final class InMemoryCommandRepository implements DeviceCommandRepository {
        private final ConcurrentHashMap<UUID, DeviceCommand> values = new ConcurrentHashMap<>();
        @Override public void save(DeviceCommand command) { values.put(command.id(), command); }
        @Override public Optional<DeviceCommand> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<DeviceCommand> findSentExpiredBefore(Instant now) { return List.of(); }
        @Override public Optional<DeviceCommand> findActiveByTypeAndBusinessRef(DeviceCommandType type, String ref) { return values.values().stream().filter(command -> command.type() == type && command.businessRef().equals(ref)).filter(command -> command.status() == DeviceCommandStatus.CREATED || command.status() == DeviceCommandStatus.SENT || command.status() == DeviceCommandStatus.ACKED).findFirst(); }
        @Override public synchronized DeviceCommand saveOrGetActive(DeviceCommand candidate) { return findActiveByTypeAndBusinessRef(candidate.type(), candidate.businessRef()).orElseGet(() -> { save(candidate); return candidate; }); }
        int size() { return values.size(); }
    }
}
