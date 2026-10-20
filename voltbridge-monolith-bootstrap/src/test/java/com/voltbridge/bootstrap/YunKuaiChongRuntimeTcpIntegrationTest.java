package com.voltbridge.bootstrap;

import com.voltbridge.business.application.device.HandleChargingProgressUseCase;
import com.voltbridge.business.application.device.HandleConnectorStatusUseCase;
import com.voltbridge.business.application.device.HandleDeviceConnectedUseCase;
import com.voltbridge.business.application.device.HandleDeviceDisconnectedUseCase;
import com.voltbridge.business.application.device.HandleHeartbeatUseCase;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.gateway.event.DeviceEventDispatcher;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.session.LocalDeviceSessionRegistry;
import com.voltbridge.gateway.session.LocalGatewayConnectionCoordinator;
import com.voltbridge.infra.cache.CaffeineConnectorRuntimeStateStore;
import com.voltbridge.infra.cache.CaffeineDeviceRouteDirectory;
import com.voltbridge.infra.cache.CaffeineDeviceRuntimeStateStore;
import com.voltbridge.infra.messaging.memory.InMemoryDeviceEventPublisher;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.yunkuaichong.YunKuaiChongProtocolAdapter;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRealtimeDataParser;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongHeartbeatHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongLoginHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRealtimeDataHandler;
import com.voltbridge.transport.tcp.NettyTcpServer;
import com.voltbridge.transport.tcp.TcpServerProperties;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongRuntimeTcpIntegrationTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final DeviceId DEVICE_ID = new DeviceId(DEVICE_CODE);
    private static final byte[] LOGIN_FRAME = HexCodec.decode(
            "6822001900012023121200001001011047562E393572313300898604D11722D0348606024E87");
    private static final String LOGIN_ACK = "680C00190002202312120000100055A1";

    @Test
    void handlesHeartbeatStatusProgressAndDisconnectOverRealTcp() throws Exception {
        RuntimeFixture fixture = fixture(Duration.ofSeconds(10));
        fixture.server().start();
        try (Socket socket = new Socket("127.0.0.1", fixture.server().boundPort())) {
            socket.setSoTimeout(5_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            output.write(LOGIN_FRAME);
            output.flush();
            assertThat(HexCodec.encode(readFrame(input))).isEqualTo(LOGIN_ACK);
            await(() -> fixture.deviceStates().find(DEVICE_ID)
                    .map(state -> state.onlineStatus() == DeviceOnlineStatus.ONLINE).orElse(false));
            Instant loginSeenAt = fixture.deviceStates().find(DEVICE_ID).orElseThrow().lastSeenAt();

            output.write(fixture.frameCodec().encode(2, 0, 0x03, heartbeatPayload()));
            output.flush();
            assertThat(fixture.frameCodec().decode(readFrame(input)).command()).isEqualTo(0x04);
            await(() -> fixture.events().stream().anyMatch(HeartbeatEvent.class::isInstance));
            assertThat(fixture.deviceStates().find(DEVICE_ID).orElseThrow().lastSeenAt())
                    .isAfterOrEqualTo(loginSeenAt);

            output.write(fixture.frameCodec().encode(3, 0, 0x13,
                    realtimePayload("", 2, true)));
            output.flush();
            await(() -> fixture.connectorStates().findConnector(DEVICE_ID, 1)
                    .map(state -> state.status() == ConnectorStatus.PLUGGED).orElse(false));

            String transactionId = "12345678901234567890123456789012";
            output.write(fixture.frameCodec().encode(4, 0, 0x13,
                    realtimePayload(transactionId, 3, true)));
            output.flush();
            await(() -> fixture.connectorStates().findProgress(DEVICE_ID, 1).isPresent());
            assertThat(fixture.connectorStates().findConnector(DEVICE_ID, 1).orElseThrow().status())
                    .isEqualTo(ConnectorStatus.CHARGING);
            assertThat(fixture.connectorStates().findProgress(DEVICE_ID, 1).orElseThrow()).satisfies(progress -> {
                assertThat(progress.transactionId()).isEqualTo(transactionId);
                assertThat(progress.outputVoltage()).isEqualByComparingTo("750.5");
                assertThat(progress.outputCurrent()).isEqualByComparingTo("123.4");
                assertThat(progress.stateOfCharge()).isEqualTo(87);
                assertThat(progress.energyKwh()).isEqualByComparingTo("12.3456");
            });
            assertThat(fixture.events()).anyMatch(ConnectorStatusEvent.class::isInstance)
                    .anyMatch(ChargingProgressEvent.class::isInstance);
        } finally {
            fixture.server().stop();
        }
        await(() -> fixture.deviceStates().find(DEVICE_ID)
                .map(state -> state.onlineStatus() == DeviceOnlineStatus.OFFLINE).orElse(false));
    }

    @Test
    void marksDeviceOfflineAfterConfiguredReaderIdleTimeout() throws Exception {
        RuntimeFixture fixture = fixture(Duration.ofMillis(150));
        fixture.server().start();
        try (Socket socket = new Socket("127.0.0.1", fixture.server().boundPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(LOGIN_FRAME);
            socket.getOutputStream().flush();
            assertThat(HexCodec.encode(readFrame(socket.getInputStream()))).isEqualTo(LOGIN_ACK);
            await(() -> fixture.deviceStates().find(DEVICE_ID)
                    .map(state -> state.onlineStatus() == DeviceOnlineStatus.OFFLINE).orElse(false));
            assertThat(fixture.metrics().offlineTimeouts()).hasValue(1);
        } finally {
            fixture.server().stop();
        }
    }

    private RuntimeFixture fixture(Duration idleTimeout) {
        TimeProvider timeProvider = Instant::now;
        CaffeineDeviceRuntimeStateStore deviceStates = new CaffeineDeviceRuntimeStateStore();
        CaffeineConnectorRuntimeStateStore connectorStates = new CaffeineConnectorRuntimeStateStore();
        CountingMetrics metrics = new CountingMetrics();
        ChargingDeviceRepository devices = new RegisteredDeviceRepository();
        HandleDeviceConnectedUseCase connected = new HandleDeviceConnectedUseCase(devices, deviceStates);
        HandleDeviceDisconnectedUseCase disconnected = new HandleDeviceDisconnectedUseCase(deviceStates, metrics);
        HandleHeartbeatUseCase heartbeat = new HandleHeartbeatUseCase(deviceStates, metrics);
        HandleConnectorStatusUseCase connectorStatus = new HandleConnectorStatusUseCase(connectorStates, metrics);
        HandleChargingProgressUseCase progress = new HandleChargingProgressUseCase(connectorStates, metrics);
        List<DeviceEvent> events = new CopyOnWriteArrayList<>();
        DeviceEventHandler<DeviceEvent> recorder = events::add;
        DeviceEventDispatcher dispatcher = new DeviceEventDispatcher(new InMemoryDeviceEventPublisher(
                List.of(connected, disconnected, heartbeat, connectorStatus, progress, recorder)));

        YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
        YunKuaiChongLoginHandler loginHandler = new YunKuaiChongLoginHandler(new YunKuaiChongLoginParser(),
                new YunKuaiChongLoginAckEncoder(frameCodec), timeProvider);
        YunKuaiChongHeartbeatHandler heartbeatHandler = new YunKuaiChongHeartbeatHandler(
                new YunKuaiChongHeartbeatParser(), new YunKuaiChongHeartbeatAckEncoder(frameCodec), timeProvider);
        YunKuaiChongRealtimeDataHandler realtimeHandler = new YunKuaiChongRealtimeDataHandler(
                new YunKuaiChongRealtimeDataParser(), timeProvider);
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(frameCodec,
                new YunKuaiChongCommandRouter(List.of(loginHandler, heartbeatHandler, realtimeHandler)),
                new YunKuaiChongOutboundCommandRouter(List.of()));
        LocalDeviceSessionRegistry sessions = new LocalDeviceSessionRegistry();
        CaffeineDeviceRouteDirectory routes = new CaffeineDeviceRouteDirectory();
        LocalGatewayConnectionCoordinator coordinator = new LocalGatewayConnectionCoordinator(
                sessions, routes, new ProtocolRegistry(List.of(adapter)), connected, dispatcher,
                timeProvider, "runtime-test-node", Duration.ofMinutes(10));
        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        properties.setReaderIdleTimeout(idleTimeout);
        return new RuntimeFixture(new NettyTcpServer(properties, List.of(coordinator)), frameCodec,
                deviceStates, connectorStates, metrics, events);
    }

    private static byte[] heartbeatPayload() {
        return ByteBuffer.allocate(9).put(BcdCodec.encode(DEVICE_CODE))
                .put(BcdCodec.encodeByte(1)).put((byte) 0).array();
    }

    private static byte[] realtimePayload(String transactionId, int status, boolean plugged) {
        ByteBuffer payload = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(transactionId.isBlank() ? "0".repeat(32) : transactionId));
        payload.put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1));
        payload.put((byte) status).put((byte) 0).put((byte) (plugged ? 1 : 0));
        payload.putShort((short) 7505).putShort((short) 1234).put((byte) 35);
        payload.putLong(123456789L).put((byte) 87).put((byte) 42);
        payload.putShort((short) 125).putShort((short) 20);
        payload.putInt(123456).putInt(123500).putInt(78900).putShort((short) 0);
        return payload.array();
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

    private record RuntimeFixture(NettyTcpServer server, YunKuaiChongFrameCodec frameCodec,
                                  CaffeineDeviceRuntimeStateStore deviceStates,
                                  CaffeineConnectorRuntimeStateStore connectorStates,
                                  CountingMetrics metrics, List<DeviceEvent> events) {
    }

    private static final class CountingMetrics implements DeviceRuntimeMetrics {
        private final AtomicInteger offlineTimeouts = new AtomicInteger();

        @Override
        public void offlineTimeout() {
            offlineTimeouts.incrementAndGet();
        }

        AtomicInteger offlineTimeouts() {
            return offlineTimeouts;
        }
    }

    private static final class RegisteredDeviceRepository implements ChargingDeviceRepository {
        private final ChargingDevice device = new ChargingDevice(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), DEVICE_CODE, "Runtime Test Device", DeviceProtocol.YUN_KUAI_CHONG_150,
                "1.5", "REGISTERED", Instant.now(), Instant.now(), 0);

        @Override
        public Optional<ChargingDevice> findById(UUID id) {
            return device.id().equals(id) ? Optional.of(device) : Optional.empty();
        }

        @Override
        public Optional<ChargingDevice> findByDeviceCode(String deviceCode) {
            return DEVICE_CODE.equals(deviceCode) ? Optional.of(device) : Optional.empty();
        }

        @Override
        public boolean updateStatus(String deviceCode, String status, Instant updatedAt) {
            return false;
        }
    }
}
