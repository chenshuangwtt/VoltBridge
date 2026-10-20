package com.voltbridge.bootstrap;

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
import com.voltbridge.business.application.transaction.HandleChargingEndedUseCase;
import com.voltbridge.business.application.transaction.HandleTransactionProgressUseCase;
import com.voltbridge.business.application.transaction.HandleTransactionRecordUseCase;
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
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceEventHandler;
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
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.protocol.yunkuaichong.YunKuaiChongProtocolAdapter;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongEndChargeParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRealtimeDataParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStartEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStopEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordParser;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongEndChargeHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongHeartbeatHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongLoginHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRealtimeDataHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRemoteStartResultHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRemoteStopResultHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongTransactionRecordHandler;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.scenario.ChargingScenarioConfig;
import com.voltbridge.testing.simulator.scenario.ChargingScenarioRunner;
import com.voltbridge.testing.simulator.scenario.PlatformScenarioControl;
import com.voltbridge.testing.simulator.scenario.ScenarioResult;
import com.voltbridge.testing.simulator.yunkuaichong.YunKuaiChongV150Simulator;
import com.voltbridge.transport.tcp.NettyTcpServer;
import com.voltbridge.transport.tcp.TcpServerProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用外部 TCP 设备模拟器验证云快充远程充电全生命周期。 */
class YunKuaiChongSimulatorScenarioIntegrationTest {

    private static final String DEVICE_CODE = "20231212000010";

    @Test
    void remoteStartThenTargetCompletePersistsFinalRecordAndAcknowledgesDuplicate() throws Exception {
        ScenarioFixture fixture = ScenarioFixture.start();
        ChargingScenarioConfig config = config("ykc-remote-start-target-complete", CompletionMode.NATURAL_TARGET, true);
        try (YunKuaiChongV150Simulator simulator = fixture.simulator()) {
            ScenarioResult result = new ChargingScenarioRunner().runRemoteStartScenario(simulator, config, fixture.control());

            assertThat(result.finalStatus()).isEqualTo("COMPLETED");
            assertThat(result.samplesSent()).isGreaterThan(2);
            assertThat(result.trace()).anyMatch(item -> item.contains("Duplicate TransactionRecord acknowledged"));
            ChargingTransaction transaction = fixture.transactions.findById(result.chargingTransactionId()).orElseThrow();
            assertThat(transaction.status()).isEqualTo(ChargingTransactionStatus.COMPLETED);
            assertThat(transaction.energyKwh()).isEqualByComparingTo(config.targetEnergyKwh());
            assertThat(transaction.endMeterKwh()).isEqualByComparingTo(config.initialMeterKwh().add(config.targetEnergyKwh()));
            assertThat(transaction.latestSoc()).isEqualTo(config.targetSoc());
            assertThat(fixture.transactions.size()).isEqualTo(1);
            assertThat(fixture.commands.findById(result.startCommandId()).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SUCCEEDED);
            BillingRecord bill = fixture.billing.requireByTransactionId(transaction.id());
            assertThat(bill.totalAmount()).isEqualByComparingTo("6.00");
            assertThat(bill.lineItems()).hasSize(2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void remoteStartThenRemoteStopCompletesOnlyAfterEndAndFinalRecord() throws Exception {
        ScenarioFixture fixture = ScenarioFixture.start();
        ChargingScenarioConfig config = config("ykc-remote-start-remote-stop", CompletionMode.REMOTE_STOP, false);
        try (YunKuaiChongV150Simulator simulator = fixture.simulator()) {
            ScenarioResult result = new ChargingScenarioRunner().runRemoteStartScenario(simulator, config, fixture.control());

            assertThat(result.stopCommandId()).isNotNull();
            assertThat(fixture.commands.findById(result.stopCommandId()).orElseThrow().status())
                    .isEqualTo(DeviceCommandStatus.SUCCEEDED);
            assertThat(fixture.transactions.findById(result.chargingTransactionId()).orElseThrow().status())
                    .isEqualTo(ChargingTransactionStatus.COMPLETED);
            assertThat(result.trace()).anyMatch(item -> item.contains("EndCharge sent"));
            assertThat(result.trace()).anyMatch(item -> item.contains("Final TransactionRecord acknowledged"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void disconnectDuringChargingMarksDeviceOfflineWithoutCompletingTransaction() throws Exception {
        ScenarioFixture fixture = ScenarioFixture.start();
        try (YunKuaiChongV150Simulator simulator = fixture.simulator()) {
            simulator.connect();
            simulator.loginOrBoot();
            simulator.heartbeat();
            simulator.connectorReady();
            UUID startId = fixture.control().requestRemoteStart();
            simulator.awaitRemoteStart(Duration.ofSeconds(3));
            simulator.acceptRemoteStart();
            fixture.control().awaitStartAcknowledged(startId);
            simulator.beginCharging(new com.voltbridge.testing.simulator.scenario.DeterministicChargingProfile(
                    config("ykc-disconnect", CompletionMode.NATURAL_TARGET, false)).samples().getFirst());
            fixture.control().awaitTransactionCharging();
            simulator.disconnect();
            await(() -> fixture.deviceStates.find(new DeviceId(DEVICE_CODE))
                    .map(state -> state.onlineStatus() == DeviceOnlineStatus.OFFLINE).orElse(false));
            ChargingTransaction transaction = fixture.transactions.findByCommandId(startId).orElseThrow();
            assertThat(transaction.status()).isEqualTo(ChargingTransactionStatus.CHARGING);
        } finally {
            fixture.close();
        }
    }

    private static ChargingScenarioConfig config(String name, CompletionMode completion, boolean duplicate) {
        return new ChargingScenarioConfig(name, DEVICE_CODE, 1, Duration.ofMillis(300), Duration.ofMinutes(30),
                Duration.ofMillis(50), 20, 80, new BigDecimal("0.0000"), new BigDecimal("6.0000"),
                new BigDecimal("12345.0000"), new BigDecimal("7.2"), completion,
                Instant.parse("2026-10-19T08:00:00Z"), duplicate);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        assertThat(condition.getAsBoolean()).as("condition should become true before timeout").isTrue();
    }

    private static final class ScenarioFixture implements AutoCloseable {
        private final NettyTcpServer server;
        private final DeviceController controller;
        private final InMemoryCommandRepository commands;
        private final InMemoryChargingTransactionRepository transactions;
        private final CaffeineDeviceRuntimeStateStore deviceStates;
        private final BillingScenarioSupport billing;
        private final Control control;

        private ScenarioFixture(NettyTcpServer server, DeviceController controller, InMemoryCommandRepository commands,
                                InMemoryChargingTransactionRepository transactions, CaffeineDeviceRuntimeStateStore deviceStates,
                                BillingScenarioSupport billing) {
            this.server = server; this.controller = controller; this.commands = commands;
            this.transactions = transactions; this.deviceStates = deviceStates;
            this.billing = billing;
            this.control = new Control(controller, commands, transactions);
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
            ChargingStartedService chargingStarted = new ChargingStartedService(transactions, commands);
            List<DeviceEventHandler<? extends DeviceEvent>> handlers = List.of(connected,
                    new HandleDeviceDisconnectedUseCase(deviceStates, DeviceRuntimeMetrics.NOOP),
                    new HandleHeartbeatUseCase(deviceStates, DeviceRuntimeMetrics.NOOP),
                    new HandleConnectorStatusUseCase(connectorStates, DeviceRuntimeMetrics.NOOP),
                    new HandleChargingProgressUseCase(connectorStates, DeviceRuntimeMetrics.NOOP),
                    new HandleCommandResultUseCase(commands), new HandleTransactionProgressUseCase(resolver, chargingStarted),
                    new HandleChargingEndedUseCase(resolver, transactions, commands),
                    new HandleTransactionRecordUseCase(resolver, transactions, commands, billing::onBusinessEvent));
            DeviceEventDispatcher dispatcher = new DeviceEventDispatcher(new InMemoryDeviceEventPublisher(handlers));

            YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
            YunKuaiChongPendingCommandRegistry pending = new YunKuaiChongPendingCommandRegistry(clock, Duration.ofMinutes(10));
            YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(frameCodec,
                    new YunKuaiChongCommandRouter(List.of(
                            new YunKuaiChongLoginHandler(new YunKuaiChongLoginParser(), new YunKuaiChongLoginAckEncoder(frameCodec), clock),
                            new YunKuaiChongHeartbeatHandler(new YunKuaiChongHeartbeatParser(), new YunKuaiChongHeartbeatAckEncoder(frameCodec), clock),
                            new YunKuaiChongRemoteStartResultHandler(pending, clock),
                            new YunKuaiChongRemoteStopResultHandler(pending, clock),
                            new YunKuaiChongRealtimeDataHandler(new YunKuaiChongRealtimeDataParser(), clock),
                            new YunKuaiChongEndChargeHandler(new YunKuaiChongEndChargeParser(), clock),
                            new YunKuaiChongTransactionRecordHandler(new YunKuaiChongTransactionRecordParser("Asia/Shanghai"),
                                    new YunKuaiChongTransactionRecordAckEncoder(frameCodec), clock))),
                    new YunKuaiChongOutboundCommandRouter(List.of(new YunKuaiChongRemoteStartEncoder(frameCodec, pending),
                            new YunKuaiChongRemoteStopEncoder(frameCodec, pending))));
            LocalDeviceSessionRegistry sessions = new LocalDeviceSessionRegistry();
            CaffeineDeviceRouteDirectory routes = new CaffeineDeviceRouteDirectory();
            LocalGatewayConnectionCoordinator coordinator = new LocalGatewayConnectionCoordinator(sessions, routes,
                    new ProtocolRegistry(List.of(adapter)), connected, dispatcher, clock, "scenario-ykc", Duration.ofMinutes(10));
            DeviceCommandDispatcher gateway = new DeviceCommandDispatcher(new DeviceRouter(routes, clock), sessions,
                    new ProtocolRegistry(List.of(adapter)));
            DeviceController controller = new DeviceController(new FindChargingDeviceUseCase(devices),
                    new StartChargingUseCase(devices, connectors, commands, transactions, gateway, clock, Duration.ofSeconds(5)),
                    new StopChargingUseCase(devices, connectors, transactions, commands, gateway, clock, Duration.ofSeconds(5)));
            TcpServerProperties properties = new TcpServerProperties();
            properties.setHost("127.0.0.1"); properties.setPort(0);
            NettyTcpServer server = new NettyTcpServer(properties, List.of(coordinator));
            server.start();
            return new ScenarioFixture(server, controller, commands, transactions, deviceStates, billing);
        }

        YunKuaiChongV150Simulator simulator() { return new YunKuaiChongV150Simulator("127.0.0.1", server.boundPort(), DEVICE_CODE, 1); }
        PlatformScenarioControl control() { return control; }
        @Override public void close() { server.stop(); }
    }

    private static final class Control implements PlatformScenarioControl {
        private final DeviceController controller; private final InMemoryCommandRepository commands;
        private final InMemoryChargingTransactionRepository transactions; private UUID startId;
        private Control(DeviceController controller, InMemoryCommandRepository commands, InMemoryChargingTransactionRepository transactions) {
            this.controller = controller; this.commands = commands; this.transactions = transactions;
        }
        @Override public UUID requestRemoteStart() {
            startId = controller.start(DEVICE_CODE, new DeviceController.StartChargingRequest("01", new BigDecimal("50.00"),
                    "1234567890123456", "1234567890123456", "SIM-YKC-START")).data().commandId(); return startId;
        }
        @Override public UUID requestRemoteStop() { return controller.stop(DEVICE_CODE, new DeviceController.StopChargingRequest("01")).data().commandId(); }
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
        ChargingDevice device() { return new ChargingDevice(id, tenantId, stationId, DEVICE_CODE, "YKC Simulator Device", DeviceProtocol.YUN_KUAI_CHONG_150, "1.5", "REGISTERED", Instant.now(), Instant.now(), 0); }
        @Override public Optional<ChargingDevice> findById(UUID value) { return id.equals(value) ? Optional.of(device()) : Optional.empty(); }
        @Override public Optional<ChargingDevice> findByDeviceCode(String code) { return DEVICE_CODE.equals(code) ? Optional.of(device()) : Optional.empty(); }
        @Override public boolean updateStatus(String code, String status, Instant updatedAt) { return DEVICE_CODE.equals(code); }
    }

    private static final class InMemoryConnectorRepository implements ConnectorRepository {
        private final Connector connector;
        private InMemoryConnectorRepository(ChargingDevice device) { connector = new Connector(UUID.randomUUID(), device.tenantId(), device.stationId(), device.id(), 1, "SIM-YKC-01", "DC", new BigDecimal("120"), "AVAILABLE", Instant.now(), Instant.now()); }
        @Override public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) { return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo ? Optional.of(connector) : Optional.empty(); }
    }

    private static final class InMemoryCommandRepository implements DeviceCommandRepository {
        private final ConcurrentHashMap<UUID, DeviceCommand> values = new ConcurrentHashMap<>();
        @Override public void save(DeviceCommand command) { values.put(command.id(), command); }
        @Override public Optional<DeviceCommand> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public List<DeviceCommand> findSentExpiredBefore(Instant now) { return List.of(); }
        @Override public Optional<DeviceCommand> findActiveByTypeAndBusinessRef(DeviceCommandType type, String ref) { return values.values().stream().filter(command -> command.type() == type && command.businessRef().equals(ref)).filter(command -> command.status() == DeviceCommandStatus.CREATED || command.status() == DeviceCommandStatus.SENT || command.status() == DeviceCommandStatus.ACKED).findFirst(); }
        @Override public synchronized DeviceCommand saveOrGetActive(DeviceCommand candidate) { return findActiveByTypeAndBusinessRef(candidate.type(), candidate.businessRef()).orElseGet(() -> { save(candidate); return candidate; }); }
    }
}
