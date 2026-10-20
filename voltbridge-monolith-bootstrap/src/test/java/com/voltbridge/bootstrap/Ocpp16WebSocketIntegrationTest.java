package com.voltbridge.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.command.HandleCommandResultUseCase;
import com.voltbridge.business.application.device.HandleChargingProgressUseCase;
import com.voltbridge.business.application.device.HandleConnectorStatusUseCase;
import com.voltbridge.business.application.device.HandleDeviceConnectedUseCase;
import com.voltbridge.business.application.device.HandleDeviceDisconnectedUseCase;
import com.voltbridge.business.application.device.HandleHeartbeatUseCase;
import com.voltbridge.business.application.transaction.ChargingStartedService;
import com.voltbridge.business.application.transaction.ChargingTransactionResolver;
import com.voltbridge.business.application.transaction.HandleTransactionCommandResultUseCase;
import com.voltbridge.business.application.transaction.HandleTransactionProgressUseCase;
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
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
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
import com.voltbridge.transport.websocket.NettyWebSocketServer;
import com.voltbridge.transport.websocket.WebSocketServerProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用 JDK 真实 WebSocket 客户端验证 OCPP 1.6J 与统一业务主线的完整闭环。 */
class Ocpp16WebSocketIntegrationTest {

    private static final String DEVICE_CODE = "OCPP-TEST-001";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesRemoteAndDeviceInitiatedTransactionsOverRealWebSocket() throws Exception {
        // Given：组装真实 WebSocket、OCPP 动作处理器和统一命令/交易业务主线。
        TimeProvider timeProvider = Instant::now;
        InMemoryDeviceRepository devices = new InMemoryDeviceRepository();
        InMemoryConnectorRepository connectors = new InMemoryConnectorRepository(devices.device());
        InMemoryCommandRepository commands = new InMemoryCommandRepository();
        InMemoryChargingTransactionRepository transactions = new InMemoryChargingTransactionRepository();
        CaffeineDeviceRuntimeStateStore deviceStates = new CaffeineDeviceRuntimeStateStore();
        CaffeineConnectorRuntimeStateStore connectorStates = new CaffeineConnectorRuntimeStateStore();

        HandleDeviceConnectedUseCase connected = new HandleDeviceConnectedUseCase(devices, deviceStates);
        ChargingTransactionResolver resolver = new ChargingTransactionResolver(devices, connectors, transactions);
        ChargingStartedService chargingStarted = new ChargingStartedService(transactions, commands);
        StartDeviceTransactionUseCase transactionStart = new StartDeviceTransactionUseCase(
                devices, connectors, transactions, new AtomicLong(1000)::incrementAndGet, chargingStarted);
        List<DeviceEventHandler<? extends DeviceEvent>> handlers = List.of(
                connected,
                new HandleDeviceDisconnectedUseCase(deviceStates, DeviceRuntimeMetrics.NOOP),
                new HandleHeartbeatUseCase(deviceStates, DeviceRuntimeMetrics.NOOP),
                new HandleConnectorStatusUseCase(connectorStates, DeviceRuntimeMetrics.NOOP),
                new HandleChargingProgressUseCase(connectorStates, DeviceRuntimeMetrics.NOOP),
                new HandleCommandResultUseCase(commands),
                new HandleTransactionCommandResultUseCase(transactions),
                new HandleTransactionProgressUseCase(resolver, chargingStarted),
                new HandleTransactionRecordUseCase(resolver, transactions, commands));
        DeviceEventDispatcher eventDispatcher = new DeviceEventDispatcher(
                new InMemoryDeviceEventPublisher(handlers));

        Ocpp16Properties ocppProperties = new Ocpp16Properties();
        ocppProperties.setHeartbeatInterval(Duration.ofSeconds(30));
        ocppProperties.setRequestTimeout(Duration.ofMinutes(1));
        Ocpp16FrameCodec codec = new Ocpp16FrameCodec(objectMapper);
        List<Ocpp16ActionHandler> actionHandlers = List.of(
                new BootNotificationHandler(codec, timeProvider, ocppProperties),
                new HeartbeatHandler(codec, timeProvider),
                new StatusNotificationHandler(codec, timeProvider),
                new StartTransactionHandler(codec, transactionStart, timeProvider),
                new MeterValuesHandler(codec, timeProvider),
                new StopTransactionHandler(codec, timeProvider));
        Ocpp16ProtocolAdapter adapter = new Ocpp16ProtocolAdapter(codec,
                new Ocpp16ActionHandlerRegistry(actionHandlers),
                new Ocpp16PendingRequestRegistry(timeProvider, ocppProperties), timeProvider);
        ProtocolRegistry protocols = new ProtocolRegistry(List.of(adapter));
        LocalDeviceSessionRegistry sessions = new LocalDeviceSessionRegistry();
        CaffeineDeviceRouteDirectory routes = new CaffeineDeviceRouteDirectory();
        LocalGatewayConnectionCoordinator coordinator = new LocalGatewayConnectionCoordinator(
                sessions, routes, protocols, connected, eventDispatcher, timeProvider,
                "ocpp-integration-node", Duration.ofMinutes(10));
        DeviceCommandDispatcher commandGateway = new DeviceCommandDispatcher(
                new DeviceRouter(routes, timeProvider), sessions, protocols);
        StartChargingUseCase startCharging = new StartChargingUseCase(devices, connectors, commands,
                transactions, commandGateway, timeProvider, Duration.ofSeconds(5));
        StopChargingUseCase stopCharging = new StopChargingUseCase(devices, connectors, transactions,
                commands, commandGateway, timeProvider, Duration.ofSeconds(5));

        WebSocketServerProperties serverProperties = new WebSocketServerProperties();
        serverProperties.setHost("127.0.0.1");
        serverProperties.setPort(0);
        NettyWebSocketServer server = new NettyWebSocketServer(serverProperties, List.of(coordinator));
        server.start();
        QueueingWebSocketListener listener = new QueueingWebSocketListener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("ocpp1.6")
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + server.boundPort() + "/ocpp/" + DEVICE_CODE), listener)
                .get(5, TimeUnit.SECONDS);
        try {
            // BootNotification 通过后才激活设备路由；错误子协议在 HTTP 升级阶段直接拒绝。
            assertThat(socket.getSubprotocol()).isEqualTo("ocpp1.6");
            assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
                    .subprotocols("ocpp2.0")
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.boundPort() + "/ocpp/" + DEVICE_CODE),
                            new QueueingWebSocketListener()).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
            send(socket, "[2,\"boot-1\",\"BootNotification\",{\"chargePointVendor\":\"VoltBridge\",\"chargePointModel\":\"Simulator\"}]");
            JsonNode boot = receive(listener);
            assertThat(boot.get(0).asInt()).isEqualTo(3);
            assertThat(boot.get(1).asText()).isEqualTo("boot-1");
            assertThat(boot.get(2).get("status").asText()).isEqualTo("Accepted");
            assertThat(boot.get(2).get("interval").asInt()).isEqualTo(30);

            DeviceId deviceId = new DeviceId(DEVICE_CODE);
            await(() -> routes.find(deviceId).isPresent());
            assertThat(deviceStates.find(deviceId).orElseThrow().onlineStatus().name()).isEqualTo("ONLINE");
            assertThat(sessions.find(routes.find(deviceId).orElseThrow().sessionId())).isPresent();

            // 常规上行 CALL 转为标准事件后，再生成对应 CALLRESULT。
            send(socket, "[2,\"heartbeat-1\",\"Heartbeat\",{}]");
            assertThat(receive(listener).get(2).hasNonNull("currentTime")).isTrue();
            send(socket, "[2,\"status-1\",\"StatusNotification\",{\"connectorId\":1,\"errorCode\":\"NoError\",\"status\":\"Preparing\"}]");
            receive(listener);
            await(() -> connectorStates.findConnector(deviceId, 1)
                    .map(state -> state.status() == ConnectorStatus.PLUGGED).orElse(false));

            send(socket, "[2,\"unknown-action\",\"NotImplementedAction\",{}]");
            JsonNode callError = receive(listener);
            assertThat(callError.get(0).asInt()).isEqualTo(4);
            assertThat(callError.get(1).asText()).isEqualTo("unknown-action");
            assertThat(callError.get(2).asText()).isEqualTo("NotSupported");
            send(socket, "[3,\"late-unknown-id\",{\"status\":\"Accepted\"}]");
            send(socket, "[2,\"heartbeat-after-late\",\"Heartbeat\",{}]");
            JsonNode heartbeatAfterLate = receive(listener);
            assertThat(heartbeatAfterLate.get(1).asText()).isEqualTo("heartbeat-after-late");

            // RemoteStartTransaction 的 Accepted 只表示 ACKED，StartTransaction 才确认真实开始。
            UUID startCommandId = startCharging.execute(DEVICE_CODE, "1", new BigDecimal("20.00"),
                    "1234567890123456", "1234567890123456", "OCPP-ORDER-001");
            JsonNode remoteStart = receive(listener);
            assertThat(remoteStart.get(2).asText()).isEqualTo("RemoteStartTransaction");
            assertThat(remoteStart.get(3).get("connectorId").asInt()).isEqualTo(1);
            assertThat(commands.findById(startCommandId).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SENT);
            send(socket, "[3,\"" + remoteStart.get(1).asText() + "\",{\"status\":\"Accepted\"}]");
            await(() -> commands.findById(startCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.ACKED).orElse(false));

            send(socket, "[2,\"start-1\",\"StartTransaction\",{\"connectorId\":1,\"idTag\":\"1234567890123456\",\"meterStart\":1000,\"timestamp\":\"2026-10-18T10:00:00Z\"}]");
            int transactionId = receive(listener).get(2).get("transactionId").asInt();
            assertThat(transactionId).isEqualTo(1001);
            await(() -> transactions.findByCommandId(startCommandId)
                    .map(transaction -> transaction.status() == ChargingTransactionStatus.CHARGING).orElse(false));
            assertThat(commands.findById(startCommandId).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SUCCEEDED);

            send(socket, meterValues("meter-1", transactionId, 2500));
            receive(listener);
            await(() -> connectorStates.findProgress(deviceId, 1).isPresent());
            var progress = connectorStates.findProgress(deviceId, 1).orElseThrow();
            assertThat(progress.energyKwh()).isEqualByComparingTo("2.5");
            assertThat(progress.outputPowerKw()).isEqualByComparingTo("7.2");
            assertThat(progress.outputVoltage()).isEqualByComparingTo("230");
            assertThat(progress.outputCurrent()).isEqualByComparingTo("16");
            assertThat(progress.stateOfCharge()).isEqualTo(45);

            // RemoteStopTransaction 的 Accepted 不结束交易，最终状态由 StopTransaction 收敛。
            UUID stopCommandId = stopCharging.execute(DEVICE_CODE, "1");
            JsonNode remoteStop = receive(listener);
            assertThat(remoteStop.get(2).asText()).isEqualTo("RemoteStopTransaction");
            assertThat(remoteStop.get(3).get("transactionId").asInt()).isEqualTo(transactionId);
            send(socket, "[3,\"" + remoteStop.get(1).asText() + "\",{\"status\":\"Accepted\"}]");
            await(() -> commands.findById(stopCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.ACKED).orElse(false));
            assertThat(transactions.findByCommandId(startCommandId).orElseThrow().status())
                    .isEqualTo(ChargingTransactionStatus.CHARGING);

            send(socket, stopTransaction("stop-1", transactionId, 3500, "Remote"));
            receive(listener);
            await(() -> transactions.findByCommandId(startCommandId)
                    .map(transaction -> transaction.status() == ChargingTransactionStatus.COMPLETED).orElse(false));
            ChargingTransaction completed = transactions.findByCommandId(startCommandId).orElseThrow();
            assertThat(completed.endMeterKwh()).isEqualByComparingTo("3.5");
            assertThat(completed.energyKwh()).isEqualByComparingTo("2.5");
            assertThat(commands.findById(stopCommandId).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SUCCEEDED);

            // 设备本地刷卡不创建远程命令，但复用同一个 ChargingTransaction 聚合。
            int commandCount = commands.size();
            send(socket, "[2,\"start-local\",\"StartTransaction\",{\"connectorId\":1,\"idTag\":\"LOCAL-CARD\",\"meterStart\":4000,\"timestamp\":\"2026-10-18T11:00:00Z\"}]");
            int localTransactionId = receive(listener).get(2).get("transactionId").asInt();
            assertThat(localTransactionId).isEqualTo(1002);
            assertThat(commands.size()).isEqualTo(commandCount);
            ChargingTransaction local = transactions.findByProtocolTransactionId(
                    DeviceProtocol.OCPP_16, devices.device().id(), Integer.toString(localTransactionId)).orElseThrow();
            assertThat(local.commandId()).isNull();
            assertThat(local.status()).isEqualTo(ChargingTransactionStatus.CHARGING);
            send(socket, meterValues("meter-local", localTransactionId, 4500));
            receive(listener);
            send(socket, stopTransaction("stop-local", localTransactionId, 5000, "Local"));
            receive(listener);
            await(() -> transactions.findById(local.id())
                    .map(transaction -> transaction.status() == ChargingTransactionStatus.COMPLETED).orElse(false));
            assertThat(commands.size()).isEqualTo(commandCount);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
            server.stop();
        }
    }

    private String meterValues(String uniqueId, int transactionId, int energyWh) {
        return "[2,\"" + uniqueId + "\",\"MeterValues\",{\"connectorId\":1,\"transactionId\":"
                + transactionId + ",\"meterValue\":[{\"timestamp\":\"2026-10-18T10:10:00Z\",\"sampledValue\":["
                + "{\"value\":\"" + energyWh + "\",\"measurand\":\"Energy.Active.Import.Register\",\"unit\":\"Wh\"},"
                + "{\"value\":\"7200\",\"measurand\":\"Power.Active.Import\",\"unit\":\"W\"},"
                + "{\"value\":\"230\",\"measurand\":\"Voltage\",\"unit\":\"V\"},"
                + "{\"value\":\"16\",\"measurand\":\"Current.Import\",\"unit\":\"A\"},"
                + "{\"value\":\"45\",\"measurand\":\"SoC\",\"unit\":\"Percent\"}]}]}]";
    }

    private String stopTransaction(String uniqueId, int transactionId, int meterStop, String reason) {
        return "[2,\"" + uniqueId + "\",\"StopTransaction\",{\"transactionId\":" + transactionId
                + ",\"meterStop\":" + meterStop + ",\"timestamp\":\"2026-10-18T10:30:00Z\",\"reason\":\""
                + reason + "\"}]";
    }

    private void send(WebSocket socket, String text) {
        socket.sendText(text, true).join();
    }

    private JsonNode receive(QueueingWebSocketListener listener) throws Exception {
        String text = listener.messages.poll(5, TimeUnit.SECONDS);
        assertThat(text).isNotNull();
        return objectMapper.readTree(text);
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class QueueingWebSocketListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder current = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                messages.add(current.toString());
                current.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }

    private static final class InMemoryDeviceRepository implements ChargingDeviceRepository {
        private final UUID id = UUID.randomUUID();
        private final UUID tenantId = UUID.randomUUID();
        private final UUID stationId = UUID.randomUUID();

        private ChargingDevice device() {
            Instant now = Instant.now();
            return new ChargingDevice(id, tenantId, stationId, DEVICE_CODE, "OCPP integration device",
                    DeviceProtocol.OCPP_16, "1.6J", "REGISTERED", now, now, 0);
        }

        @Override public Optional<ChargingDevice> findById(UUID value) {
            return id.equals(value) ? Optional.of(device()) : Optional.empty();
        }
        @Override public Optional<ChargingDevice> findByDeviceCode(String code) {
            return DEVICE_CODE.equals(code) ? Optional.of(device()) : Optional.empty();
        }
        @Override public boolean updateStatus(String code, String status, Instant updatedAt) {
            return DEVICE_CODE.equals(code);
        }
    }

    private static final class InMemoryConnectorRepository implements ConnectorRepository {
        private final Connector connector;

        private InMemoryConnectorRepository(ChargingDevice device) {
            connector = new Connector(UUID.randomUUID(), device.tenantId(), device.stationId(), device.id(),
                    1, "OCPP-CONNECTOR-01", "DC", new BigDecimal("120"), "AVAILABLE",
                    Instant.now(), Instant.now());
        }

        @Override public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) {
            return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo
                    ? Optional.of(connector) : Optional.empty();
        }
    }

    private static final class InMemoryCommandRepository implements DeviceCommandRepository {
        private final ConcurrentHashMap<UUID, DeviceCommand> values = new ConcurrentHashMap<>();

        @Override public void save(DeviceCommand command) { values.put(command.id(), command); }
        @Override public Optional<DeviceCommand> findById(UUID commandId) {
            return Optional.ofNullable(values.get(commandId));
        }
        @Override public List<DeviceCommand> findSentExpiredBefore(Instant now) {
            return values.values().stream().filter(command -> command.status() == DeviceCommandStatus.SENT)
                    .filter(command -> !command.expiredAt().isAfter(now)).toList();
        }
        @Override public Optional<DeviceCommand> findActiveByTypeAndBusinessRef(DeviceCommandType type,
                                                                                String businessRef) {
            return values.values().stream().filter(command -> command.type() == type)
                    .filter(command -> command.businessRef().equals(businessRef))
                    .filter(command -> command.status() == DeviceCommandStatus.CREATED
                            || command.status() == DeviceCommandStatus.SENT
                            || command.status() == DeviceCommandStatus.ACKED
                            || command.status() == DeviceCommandStatus.EXECUTING).findFirst();
        }
        @Override public synchronized DeviceCommand saveOrGetActive(DeviceCommand candidate) {
            return findActiveByTypeAndBusinessRef(candidate.type(), candidate.businessRef())
                    .orElseGet(() -> { save(candidate); return candidate; });
        }
        private int size() { return values.size(); }
    }
}
