package com.voltbridge.bootstrap;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.command.HandleCommandResultUseCase;
import com.voltbridge.business.application.device.HandleDeviceConnectedUseCase;
import com.voltbridge.business.application.device.HandleDeviceDisconnectedUseCase;
import com.voltbridge.business.application.transaction.ChargingStartedService;
import com.voltbridge.business.application.transaction.ChargingTransactionResolver;
import com.voltbridge.business.application.transaction.HandleChargingEndedUseCase;
import com.voltbridge.business.application.transaction.HandleTransactionCommandResultUseCase;
import com.voltbridge.business.application.transaction.HandleTransactionProgressUseCase;
import com.voltbridge.business.application.transaction.HandleTransactionRecordUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandStatus;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.gateway.event.DeviceEventDispatcher;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.session.LocalDeviceSessionRegistry;
import com.voltbridge.gateway.session.LocalGatewayConnectionCoordinator;
import com.voltbridge.infra.cache.CaffeineDeviceRouteDirectory;
import com.voltbridge.infra.cache.CaffeineDeviceRuntimeStateStore;
import com.voltbridge.infra.messaging.memory.InMemoryDeviceEventPublisher;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.YunKuaiChongProtocolAdapter;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongEndChargeParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRealtimeDataParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStartEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStopEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordParser;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongEndChargeHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongLoginHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRemoteStartResultHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRemoteStopResultHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRealtimeDataHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongTransactionRecordHandler;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.transport.tcp.NettyTcpServer;
import com.voltbridge.transport.tcp.TcpServerProperties;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongRemoteStartTcpIntegrationTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final byte[] LOGIN_FRAME = HexCodec.decode(
            "6822001900012023121200001001011047562E393572313300898604D11722D0348606024E87");
    private static final String LOGIN_ACK = "680C00190002202312120000100055A1";

    @Test
    void completesChargingTransactionAndAcknowledgesDuplicateFinalRecordsOverRealTcpSocket() throws Exception {
        // Given：以真实 TCP 通道组装命令、交易、协议关联和事件消费完整链路。
        TimeProvider timeProvider = Instant::now;
        InMemoryChargingDeviceRepository devices = new InMemoryChargingDeviceRepository();
        InMemoryConnectorRepository connectors = new InMemoryConnectorRepository(devices.device());
        InMemoryCommandRepository commands = new InMemoryCommandRepository();
        InMemoryChargingTransactionRepository transactions = new InMemoryChargingTransactionRepository();
        CaffeineDeviceRuntimeStateStore runtimeStates = new CaffeineDeviceRuntimeStateStore();
        HandleDeviceConnectedUseCase connected = new HandleDeviceConnectedUseCase(devices, runtimeStates);
        HandleDeviceDisconnectedUseCase disconnected = new HandleDeviceDisconnectedUseCase(
                runtimeStates, DeviceRuntimeMetrics.NOOP);
        HandleCommandResultUseCase commandResults = new HandleCommandResultUseCase(commands);
        ChargingTransactionResolver transactionResolver = new ChargingTransactionResolver(
                devices, connectors, transactions);
        ChargingStartedService chargingStarted = new ChargingStartedService(transactions, commands);
        HandleTransactionCommandResultUseCase transactionCommandResults =
                new HandleTransactionCommandResultUseCase(transactions);
        HandleTransactionProgressUseCase transactionProgress =
                new HandleTransactionProgressUseCase(transactionResolver, chargingStarted);
        HandleChargingEndedUseCase chargingEnded =
                new HandleChargingEndedUseCase(transactionResolver, transactions, commands);
        HandleTransactionRecordUseCase transactionRecords =
                new HandleTransactionRecordUseCase(transactionResolver, transactions, commands);
        List<DeviceEvent> events = new CopyOnWriteArrayList<>();
        DeviceEventHandler<DeviceEvent> recorder = events::add;
        DeviceEventDispatcher eventDispatcher = new DeviceEventDispatcher(
                new InMemoryDeviceEventPublisher(List.of(connected, disconnected, commandResults,
                        transactionCommandResults, transactionProgress, chargingEnded,
                        transactionRecords, recorder)));

        YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
        YunKuaiChongPendingCommandRegistry pendingCommands =
                new YunKuaiChongPendingCommandRegistry(timeProvider, Duration.ofMinutes(10));
        YunKuaiChongLoginHandler loginHandler = new YunKuaiChongLoginHandler(
                new YunKuaiChongLoginParser(), new YunKuaiChongLoginAckEncoder(frameCodec), timeProvider);
        YunKuaiChongRemoteStartResultHandler resultHandler =
                new YunKuaiChongRemoteStartResultHandler(pendingCommands, timeProvider);
        YunKuaiChongRemoteStartEncoder remoteStartEncoder =
                new YunKuaiChongRemoteStartEncoder(frameCodec, pendingCommands);
        YunKuaiChongRemoteStopResultHandler stopResultHandler =
                new YunKuaiChongRemoteStopResultHandler(pendingCommands, timeProvider);
        YunKuaiChongRemoteStopEncoder remoteStopEncoder =
                new YunKuaiChongRemoteStopEncoder(frameCodec, pendingCommands);
        YunKuaiChongRealtimeDataHandler realtimeHandler = new YunKuaiChongRealtimeDataHandler(
                new YunKuaiChongRealtimeDataParser(), timeProvider);
        YunKuaiChongEndChargeHandler endChargeHandler = new YunKuaiChongEndChargeHandler(
                new YunKuaiChongEndChargeParser(), timeProvider);
        YunKuaiChongTransactionRecordHandler transactionRecordHandler =
                new YunKuaiChongTransactionRecordHandler(
                        new YunKuaiChongTransactionRecordParser("Asia/Shanghai"),
                        new YunKuaiChongTransactionRecordAckEncoder(frameCodec), timeProvider);
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(frameCodec,
                new YunKuaiChongCommandRouter(List.of(loginHandler, resultHandler, stopResultHandler, realtimeHandler,
                        endChargeHandler, transactionRecordHandler)),
                new YunKuaiChongOutboundCommandRouter(List.of(remoteStartEncoder, remoteStopEncoder)));
        ProtocolRegistry protocolRegistry = new ProtocolRegistry(List.of(adapter));
        LocalDeviceSessionRegistry sessions = new LocalDeviceSessionRegistry();
        CaffeineDeviceRouteDirectory routes = new CaffeineDeviceRouteDirectory();
        LocalGatewayConnectionCoordinator coordinator = new LocalGatewayConnectionCoordinator(
                sessions, routes, protocolRegistry, connected, eventDispatcher,
                timeProvider, "integration-node", Duration.ofMinutes(10));
        StartChargingUseCase startCharging = new StartChargingUseCase(devices, connectors, commands, transactions,
                new com.voltbridge.gateway.command.DeviceCommandDispatcher(
                        new com.voltbridge.gateway.routing.DeviceRouter(routes, timeProvider),
                        sessions, protocolRegistry),
                timeProvider, Duration.ofSeconds(5));
        StopChargingUseCase stopCharging = new StopChargingUseCase(devices, connectors, transactions, commands,
                new com.voltbridge.gateway.command.DeviceCommandDispatcher(
                        new com.voltbridge.gateway.routing.DeviceRouter(routes, timeProvider),
                        sessions, protocolRegistry),
                timeProvider, Duration.ofSeconds(5));

        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        NettyTcpServer server = new NettyTcpServer(properties, List.of(coordinator));

        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
            // 先登录建立 DeviceRoute，所有下行命令都必须通过该路由找到当前会话。
            socket.setSoTimeout(5_000);
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            output.write(LOGIN_FRAME);
            output.flush();
            assertThat(HexCodec.encode(readFrame(input))).isEqualTo(LOGIN_ACK);

            DeviceId deviceId = new DeviceId(DEVICE_CODE);
            await(() -> routes.find(deviceId).isPresent());
            DeviceRoute route = routes.find(deviceId).orElseThrow();
            assertThat(sessions.find(route.sessionId())).isPresent();

            // 远程启动：写入网络只到 SENT，0x33 成功响应只到 ACKED。
            UUID commandId = startCharging.execute(DEVICE_CODE, "01", new BigDecimal("50.00"),
                    "5678901234567890", "5678901234567890", "ORDER-INTEGRATION-001");
            byte[] remoteStartBytes = readFrame(input);
            YunKuaiChongFrame remoteStart = frameCodec.decode(remoteStartBytes);
            assertThat(remoteStart.command()).isEqualTo(0x34);
            assertThat(remoteStart.payload()).hasSize(44);
            assertThat(HexCodec.encode(Arrays.copyOfRange(remoteStart.payload(), 16, 23))).isEqualTo(DEVICE_CODE);
            assertThat(HexCodec.encode(new byte[]{remoteStart.payload()[23]})).isEqualTo("01");
            await(() -> commands.findById(commandId)
                    .map(command -> command.status() == DeviceCommandStatus.SENT).orElse(false));
            String tradeNo = BcdCodec.decode(Arrays.copyOfRange(remoteStart.payload(), 0, 16));

            byte[] resultPayload = new byte[26];
            System.arraycopy(remoteStart.payload(), 0, resultPayload, 0, 24);
            resultPayload[24] = 0x01;
            resultPayload[25] = 0x00;
            byte[] resultFrame = frameCodec.encode(2, 0, 0x33, resultPayload);
            output.write(resultFrame);
            output.flush();
            await(() -> commands.findById(commandId)
                    .map(command -> command.status() == DeviceCommandStatus.ACKED).orElse(false));
            DeviceCommand acked = commands.findById(commandId).orElseThrow();
            assertThat(acked.ackedAt()).isNotNull();
            assertThat(acked.completedAt()).isNull();

            output.write(resultFrame);
            output.flush();
            await(() -> events.stream().filter(CommandResultEvent.class::isInstance).count() >= 2);
            assertThat(commands.findById(commandId).orElseThrow().status()).isEqualTo(DeviceCommandStatus.ACKED);
            assertThat(events.stream().filter(CommandResultEvent.class::isInstance)
                    .map(CommandResultEvent.class::cast).filter(CommandResultEvent::duplicate).count()).isEqualTo(1);

            // 实时充电数据才是“已经开始充电”的业务证据，交易和启动命令在此推进。
            output.write(frameCodec.encode(3, 0, 0x13, realtimePayload(tradeNo)));
            output.flush();
            await(() -> transactions.findById(transactions.findByCommandId(commandId).orElseThrow().id())
                    .map(transaction -> transaction.status() == ChargingTransactionStatus.CHARGING).orElse(false));
            await(() -> commands.findById(commandId)
                    .map(command -> command.status() == DeviceCommandStatus.SUCCEEDED).orElse(false));
            assertThat(commands.findById(commandId).orElseThrow().status()).isEqualTo(DeviceCommandStatus.SUCCEEDED);

            // 远程停充同样区分设备接受与真实结束，并验证重复业务请求的幂等性。
            UUID stopCommandId = stopCharging.execute(DEVICE_CODE, "01");
            assertThat(stopCharging.execute(DEVICE_CODE, "1")).isEqualTo(stopCommandId);
            byte[] remoteStopBytes = readFrame(input);
            YunKuaiChongFrame remoteStop = frameCodec.decode(remoteStopBytes);
            assertThat(remoteStop.command()).isEqualTo(0x36);
            assertThat(remoteStop.payload()).containsExactly(BcdCodec.encode(DEVICE_CODE)[0],
                    BcdCodec.encode(DEVICE_CODE)[1], BcdCodec.encode(DEVICE_CODE)[2],
                    BcdCodec.encode(DEVICE_CODE)[3], BcdCodec.encode(DEVICE_CODE)[4],
                    BcdCodec.encode(DEVICE_CODE)[5], BcdCodec.encode(DEVICE_CODE)[6],
                    BcdCodec.encodeByte(1));
            await(() -> commands.findById(stopCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.SENT).orElse(false));

            byte[] stopResultFrame = frameCodec.encode(4, 0, 0x35, remoteStopResultPayload(0x01, 0x00));
            output.write(stopResultFrame);
            output.flush();
            await(() -> commands.findById(stopCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.ACKED).orElse(false));
            assertThat(transactions.findByCommandId(commandId).orElseThrow().status())
                    .isEqualTo(ChargingTransactionStatus.CHARGING);
            output.write(stopResultFrame);
            output.flush();
            await(() -> events.stream().filter(CommandResultEvent.class::isInstance).count() >= 4);
            assertThat(commands.findById(stopCommandId).orElseThrow().status())
                    .isEqualTo(DeviceCommandStatus.ACKED);

            // 0x19 仅进入 ENDING；最终交易记录到达前不能提前完成交易。
            output.write(frameCodec.encode(5, 0, 0x19, endChargePayload(tradeNo)));
            output.flush();
            await(() -> transactions.findByCommandId(commandId)
                    .map(transaction -> transaction.status() == ChargingTransactionStatus.ENDING).orElse(false));
            await(() -> commands.findById(stopCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.SUCCEEDED).orElse(false));
            assertThat(commands.findById(stopCommandId).orElseThrow().status())
                    .isEqualTo(DeviceCommandStatus.SUCCEEDED);

            // 0x3B 是最终结算事实；业务同步落库后协议层才返回 0x40，重复记录仍幂等确认。
            byte[] transactionFrame = frameCodec.encode(6, 0, 0x3B,
                    transactionRecordPayload(tradeNo, 0x40));
            output.write(transactionFrame);
            output.flush();
            assertSuccessTransactionAck(frameCodec.decode(readFrame(input)), tradeNo, 6);
            await(() -> transactions.findByCommandId(commandId)
                    .map(transaction -> transaction.status() == ChargingTransactionStatus.COMPLETED).orElse(false));

            ChargingTransaction completed = transactions.findByCommandId(commandId).orElseThrow();
            assertThat(completed.startMeterKwh()).isEqualByComparingTo("12345.6789");
            assertThat(completed.endMeterKwh()).isEqualByComparingTo("12355.6789");
            assertThat(completed.energyKwh()).isEqualByComparingTo("10.0000");
            assertThat(completed.totalAmount()).isEqualByComparingTo("23.4567");
            assertThat(completed.startMethod()).isEqualTo(ChargingStartMethod.REMOTE);
            assertThat(completed.stopCategory()).isEqualTo(ChargingStopCategory.REMOTE_STOP);
            assertThat(completed.rawStopCode()).isEqualTo(0x40);
            assertThat(completed.vehicleVin()).isEqualTo("LTESTVIN123456789");

            output.write(transactionFrame);
            output.flush();
            assertSuccessTransactionAck(frameCodec.decode(readFrame(input)), tradeNo, 6);
            assertThat(transactions.size()).isEqualTo(1);
            assertThat(transactions.findByCommandId(commandId).orElseThrow().totalAmount())
                    .isEqualByComparingTo("23.4567");
            System.out.println("INTEGRATION_REMOTE_START_0X34=" + HexCodec.encode(remoteStartBytes));
            System.out.println("INTEGRATION_REMOTE_START_RESULT_0X33=" + HexCodec.encode(resultFrame));
            System.out.println("INTEGRATION_REMOTE_STOP_0X36=" + HexCodec.encode(remoteStopBytes));
            System.out.println("INTEGRATION_REMOTE_STOP_RESULT_0X35=" + HexCodec.encode(stopResultFrame));

            // 在同一真实连接上启动第二笔交易，验证 0x35 拒绝不会改变 CHARGING 交易。
            UUID failedStopStartCommandId = startCharging.execute(DEVICE_CODE, "01", new BigDecimal("20.00"),
                    null, null, "ORDER-INTEGRATION-STOP-FAILURE");
            YunKuaiChongFrame secondRemoteStart = frameCodec.decode(readFrame(input));
            String secondTradeNo = BcdCodec.decode(Arrays.copyOfRange(secondRemoteStart.payload(), 0, 16));
            byte[] secondStartResultPayload = new byte[26];
            System.arraycopy(secondRemoteStart.payload(), 0, secondStartResultPayload, 0, 24);
            secondStartResultPayload[24] = 0x01;
            output.write(frameCodec.encode(7, 0, 0x33, secondStartResultPayload));
            output.flush();
            await(() -> commands.findById(failedStopStartCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.ACKED).orElse(false));
            output.write(frameCodec.encode(8, 0, 0x13, realtimePayload(secondTradeNo)));
            output.flush();
            await(() -> transactions.findByCommandId(failedStopStartCommandId)
                    .map(item -> item.status() == ChargingTransactionStatus.CHARGING).orElse(false));

            UUID failedStopCommandId = stopCharging.execute(DEVICE_CODE, "01");
            assertThat(frameCodec.decode(readFrame(input)).command()).isEqualTo(0x36);
            byte[] rejectedStopFrame = frameCodec.encode(9, 0, 0x35,
                    remoteStopResultPayload(0x00, 0x02));
            output.write(rejectedStopFrame);
            output.flush();
            await(() -> commands.findById(failedStopCommandId)
                    .map(command -> command.status() == DeviceCommandStatus.FAILED).orElse(false));
            assertThat(commands.findById(failedStopCommandId).orElseThrow().failureCode())
                    .isEqualTo("NOT_CHARGING");
            assertThat(transactions.findByCommandId(failedStopStartCommandId).orElseThrow().status())
                    .isEqualTo(ChargingTransactionStatus.CHARGING);
            System.out.println("INTEGRATION_REMOTE_STOP_FAILURE_0X35=" + HexCodec.encode(rejectedStopFrame));
        } finally {
            server.stop();
        }
    }

    private static void assertSuccessTransactionAck(YunKuaiChongFrame ack, String tradeNo, int sequence) {
        assertThat(ack.command()).isEqualTo(0x40);
        assertThat(ack.sequenceNumber()).isEqualTo(sequence);
        assertThat(BcdCodec.decode(Arrays.copyOfRange(ack.payload(), 0, 16))).isEqualTo(tradeNo);
        assertThat(ack.payload()[16]).isZero();
    }

    private static byte[] realtimePayload(String tradeNo) {
        ByteBuffer payload = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(tradeNo));
        payload.put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1));
        payload.put((byte) 3).put((byte) 0).put((byte) 1);
        payload.putShort((short) 7505).putShort((short) 1234).put((byte) 35);
        payload.putLong(123456789L).put((byte) 45).put((byte) 42);
        payload.putShort((short) 125).putShort((short) 20);
        payload.putInt(123456).putInt(123500).putInt(78900).putShort((short) 0);
        return payload.array();
    }

    private static byte[] endChargePayload(String tradeNo) {
        ByteBuffer payload = ByteBuffer.allocate(39).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(tradeNo));
        payload.put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1));
        payload.put((byte) 88).putShort((short) 321).putShort((short) 418);
        payload.put((byte) -5).put((byte) 47).putShort((short) 90).putShort((short) 100);
        payload.put(BcdCodec.encode("12345678"));
        return payload.array();
    }

    private static byte[] remoteStopResultPayload(int result, int failure) {
        byte[] payload = new byte[10];
        System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 0, 7);
        payload[7] = BcdCodec.encodeByte(1);
        payload[8] = (byte) result;
        payload[9] = (byte) failure;
        return payload;
    }

    private static byte[] transactionRecordPayload(String tradeNo, int stopCode) {
        ByteBuffer payload = ByteBuffer.allocate(158).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(tradeNo));
        payload.put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1));
        payload.put(cp56(LocalDateTime.of(2026, 10, 18, 10, 15, 30, 120_000_000)));
        payload.put(cp56(LocalDateTime.of(2026, 10, 18, 11, 45, 30, 340_000_000)));
        bucket(payload, 123456, 25000, 100, 30864);
        bucket(payload, 145678, 25000, 110, 36420);
        bucket(payload, 100000, 25000, 120, 25000);
        bucket(payload, 87654, 25000, 130, 21914);
        putU40(payload, 123456789L);
        putU40(payload, 123556789L);
        payload.putInt(100000).putInt(460).putInt(234567);
        byte[] vin = "LTESTVIN123456789".getBytes(StandardCharsets.US_ASCII);
        payload.put(vin);
        payload.put((byte) 0x01);
        payload.put(cp56(LocalDateTime.of(2026, 10, 18, 11, 45, 31)));
        payload.put((byte) stopCode);
        payload.putLong(123456789L);
        return payload.array();
    }

    private static void bucket(ByteBuffer payload, int price, int energy, int loss, int amount) {
        payload.putInt(price).putInt(energy).putInt(loss).putInt(amount);
    }

    private static void putU40(ByteBuffer payload, long value) {
        for (int index = 0; index < 5; index++) {
            payload.put((byte) (value >>> (index * 8)));
        }
    }

    private static byte[] cp56(LocalDateTime value) {
        int millis = value.getSecond() * 1000 + value.getNano() / 1_000_000;
        return new byte[]{(byte) millis, (byte) (millis >>> 8), (byte) value.getMinute(),
                (byte) value.getHour(), (byte) value.getDayOfMonth(), (byte) value.getMonthValue(),
                (byte) (value.getYear() - 2000)};
    }

    private static byte[] readFrame(InputStream input) throws Exception {
        byte[] prefix = input.readNBytes(2);
        assertThat(prefix).hasSize(2);
        int dataLength = prefix[1] & 0xFF;
        byte[] remainder = input.readNBytes(dataLength + 2);
        assertThat(remainder).hasSize(dataLength + 2);
        byte[] frame = new byte[dataLength + 4];
        System.arraycopy(prefix, 0, frame, 0, 2);
        System.arraycopy(remainder, 0, frame, 2, remainder.length);
        return frame;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class InMemoryCommandRepository implements DeviceCommandRepository {
        private final ConcurrentHashMap<UUID, DeviceCommand> values = new ConcurrentHashMap<>();

        @Override
        public void save(DeviceCommand command) {
            values.put(command.id(), command);
        }

        @Override
        public Optional<DeviceCommand> findById(UUID commandId) {
            return Optional.ofNullable(values.get(commandId));
        }

        @Override
        public List<DeviceCommand> findSentExpiredBefore(Instant now) {
            return values.values().stream()
                    .filter(command -> command.status() == DeviceCommandStatus.SENT)
                    .filter(command -> !command.expiredAt().isAfter(now))
                    .toList();
        }

        @Override
        public Optional<DeviceCommand> findActiveByTypeAndBusinessRef(
                com.voltbridge.business.domain.command.DeviceCommandType type, String businessRef) {
            return values.values().stream()
                    .filter(command -> command.type() == type && command.businessRef().equals(businessRef))
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
    }

    private static final class InMemoryConnectorRepository implements ConnectorRepository {
        private final Connector connector;

        private InMemoryConnectorRepository(ChargingDevice device) {
            connector = new Connector(UUID.randomUUID(), device.tenantId(), device.stationId(), device.id(),
                    1, "CONNECTOR-01", "DC", new BigDecimal("120"), "AVAILABLE",
                    Instant.now(), Instant.now());
        }

        @Override
        public Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo) {
            return connector.deviceId().equals(deviceId) && connector.connectorNo() == connectorNo
                    ? Optional.of(connector) : Optional.empty();
        }
    }

    private static final class InMemoryChargingDeviceRepository implements ChargingDeviceRepository {
        private final UUID id = UUID.randomUUID();
        private final UUID tenantId = UUID.randomUUID();
        private final UUID stationId = UUID.randomUUID();
        private final AtomicReference<String> status = new AtomicReference<>("REGISTERED");

        private ChargingDevice device() {
            Instant now = Instant.now();
            return new ChargingDevice(id, tenantId, stationId, DEVICE_CODE, "Integration Device",
                    DeviceProtocol.YUN_KUAI_CHONG_150, "1.5", status.get(), now, now, 0);
        }

        @Override
        public Optional<ChargingDevice> findById(UUID id) {
            return this.id.equals(id) ? Optional.of(device()) : Optional.empty();
        }

        @Override
        public Optional<ChargingDevice> findByDeviceCode(String deviceCode) {
            return DEVICE_CODE.equals(deviceCode) ? Optional.of(device()) : Optional.empty();
        }

        @Override
        public boolean updateStatus(String deviceCode, String newStatus, Instant updatedAt) {
            if (!DEVICE_CODE.equals(deviceCode)) {
                return false;
            }
            status.set(newStatus);
            return true;
        }
    }
}
