package com.voltbridge.bootstrap;

import com.voltbridge.business.application.device.HandleDeviceConnectedUseCase;
import com.voltbridge.business.application.device.HandleDeviceDisconnectedUseCase;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.event.DeviceDisconnectedEvent;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.device.runtime.DeviceRuntimeState;
import com.voltbridge.gateway.event.DeviceEventDispatcher;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.session.LocalDeviceSessionRegistry;
import com.voltbridge.gateway.session.LocalGatewayConnectionCoordinator;
import com.voltbridge.infra.cache.CaffeineDeviceRouteDirectory;
import com.voltbridge.infra.cache.CaffeineDeviceRuntimeStateStore;
import com.voltbridge.infra.messaging.memory.InMemoryDeviceEventPublisher;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.yunkuaichong.YunKuaiChongProtocolAdapter;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongLoginParser;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongLoginHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.transport.tcp.NettyTcpServer;
import com.voltbridge.transport.tcp.TcpServerProperties;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongLoginTcpIntegrationTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final byte[] LOGIN_FRAME = HexCodec.decode(
            "6822001900012023121200001001011047562E393572313300898604D11722D0348606024E87");
    private static final String SUCCESS_ACK = "680C00190002202312120000100055A1";

    @Test
    void completesLoginBindingAckAndDisconnectCleanupOverRealTcpSocket() throws Exception {
        // Given：组装真实 TCP 服务器、协议适配器、会话路由和同步业务事件处理器。
        TimeProvider timeProvider = Instant::now;
        InMemoryChargingDeviceRepository repository = new InMemoryChargingDeviceRepository();
        CaffeineDeviceRuntimeStateStore runtimeStates = new CaffeineDeviceRuntimeStateStore();
        HandleDeviceConnectedUseCase connectedUseCase = new HandleDeviceConnectedUseCase(repository, runtimeStates);
        HandleDeviceDisconnectedUseCase disconnectedUseCase = new HandleDeviceDisconnectedUseCase(
                runtimeStates, DeviceRuntimeMetrics.NOOP);
        List<DeviceEvent> events = new CopyOnWriteArrayList<>();
        DeviceEventHandler<DeviceEvent> recorder = events::add;
        InMemoryDeviceEventPublisher publisher = new InMemoryDeviceEventPublisher(
                List.of(connectedUseCase, disconnectedUseCase, recorder));
        DeviceEventDispatcher eventDispatcher = new DeviceEventDispatcher(publisher);

        YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
        YunKuaiChongLoginParser loginParser = new YunKuaiChongLoginParser();
        YunKuaiChongLoginAckEncoder ackEncoder = new YunKuaiChongLoginAckEncoder(frameCodec);
        YunKuaiChongLoginHandler loginHandler = new YunKuaiChongLoginHandler(
                loginParser, ackEncoder, timeProvider);
        YunKuaiChongCommandRouter commandRouter = new YunKuaiChongCommandRouter(List.of(loginHandler));
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(
                frameCodec, commandRouter, new YunKuaiChongOutboundCommandRouter(List.of()));
        ProtocolRegistry protocolRegistry = new ProtocolRegistry(List.of(adapter));

        LocalDeviceSessionRegistry sessionRegistry = new LocalDeviceSessionRegistry();
        CaffeineDeviceRouteDirectory routeDirectory = new CaffeineDeviceRouteDirectory();
        LocalGatewayConnectionCoordinator coordinator = new LocalGatewayConnectionCoordinator(
                sessionRegistry, routeDirectory, protocolRegistry, connectedUseCase,
                eventDispatcher, timeProvider, "integration-node", Duration.ofMinutes(10));

        TcpServerProperties properties = new TcpServerProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(0);
        properties.setReaderIdleTimeout(Duration.ofSeconds(10));
        NettyTcpServer server = new NettyTcpServer(properties, List.of(coordinator));

        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
            // When：拆成两次写入登录帧，验证 TCP 粘拆包后仍能完成鉴权与协议应答。
            socket.setSoTimeout(5_000);
            OutputStream output = socket.getOutputStream();
            output.write(LOGIN_FRAME, 0, 5);
            output.flush();
            Thread.sleep(25);
            output.write(LOGIN_FRAME, 5, LOGIN_FRAME.length - 5);
            output.flush();

            byte[] ack = socket.getInputStream().readNBytes(16);
            assertThat(HexCodec.encode(ack)).isEqualTo(SUCCESS_ACK);

            // Then：鉴权成功后，设备路由、节点内会话和业务在线状态必须同时建立。
            DeviceId deviceId = new DeviceId(DEVICE_CODE);
            await(() -> routeDirectory.find(deviceId).isPresent());
            DeviceRoute route = routeDirectory.find(deviceId).orElseThrow();
            assertThat(route.protocol()).isEqualTo(DeviceProtocol.YUN_KUAI_CHONG_150);
            assertThat(sessionRegistry.find(route.sessionId())).isPresent();
            assertThat(sessionRegistry.find(route.sessionId()).orElseThrow().deviceId()).contains(deviceId);
            assertThat(repository.lookupCount()).isGreaterThanOrEqualTo(1);
            assertThat(runtimeStates.find(deviceId).map(DeviceRuntimeState::onlineStatus))
                    .contains(com.voltbridge.device.model.DeviceOnlineStatus.ONLINE);
            assertThat(events).anyMatch(DeviceConnectedEvent.class::isInstance);

            // And：物理连接断开后，只清理属于该会话世代的路由并发布离线事件。
            socket.close();
            await(() -> sessionRegistry.size() == 0 && routeDirectory.find(deviceId).isEmpty());
            await(() -> runtimeStates.find(deviceId)
                    .map(DeviceRuntimeState::onlineStatus)
                    .filter(com.voltbridge.device.model.DeviceOnlineStatus.OFFLINE::equals).isPresent());
            assertThat(events).anyMatch(DeviceDisconnectedEvent.class::isInstance);
        } finally {
            server.stop();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class InMemoryChargingDeviceRepository implements ChargingDeviceRepository {

        private final AtomicReference<String> status = new AtomicReference<>("REGISTERED");
        private final AtomicInteger lookupCount = new AtomicInteger();

        @Override
        public Optional<ChargingDevice> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<ChargingDevice> findByDeviceCode(String deviceCode) {
            lookupCount.incrementAndGet();
            if (!DEVICE_CODE.equals(deviceCode)) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            return Optional.of(new ChargingDevice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    DEVICE_CODE, "Integration Device", DeviceProtocol.YUN_KUAI_CHONG_150,
                    "1.5", status.get(), now, now, 0));
        }

        @Override
        public boolean updateStatus(String deviceCode, String newStatus, Instant updatedAt) {
            if (!DEVICE_CODE.equals(deviceCode)) {
                return false;
            }
            status.set(newStatus);
            return true;
        }

        int lookupCount() {
            return lookupCount.get();
        }

        String status() {
            return status.get();
        }
    }
}
