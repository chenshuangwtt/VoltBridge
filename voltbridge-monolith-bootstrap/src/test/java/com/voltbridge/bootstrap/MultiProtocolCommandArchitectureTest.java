package com.voltbridge.bootstrap;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.gateway.command.DeviceCommandDispatcher;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.DeviceRouteDirectory;
import com.voltbridge.gateway.routing.DeviceRouter;
import com.voltbridge.gateway.session.DeviceSession;
import com.voltbridge.gateway.session.DeviceSessionRegistry;
import com.voltbridge.protocol.common.spi.ProtocolAdapter;
import com.voltbridge.protocol.ocpp16.Ocpp16ProtocolAdapter;
import com.voltbridge.protocol.yunkuaichong.YunKuaiChongProtocolAdapter;
import com.voltbridge.transport.api.BinaryTransportPayload;
import com.voltbridge.transport.api.TextTransportPayload;
import com.voltbridge.transport.api.TransportEndpointId;
import com.voltbridge.transport.api.TransportSession;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从同一业务启停用例贯穿到协议注册表，验证协议差异只发生在网关适配阶段。
 */
class MultiProtocolCommandArchitectureTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @ParameterizedTest
    @EnumSource(value = DeviceProtocol.class, names = {"YUN_KUAI_CHONG_150", "OCPP_16"})
    void startUseCaseRoutesCanonicalCommandToSelectedProtocolAdapter(DeviceProtocol protocol) {
        Fixture fixture = fixture(protocol);
        StartChargingUseCase useCase = new StartChargingUseCase(fixture.deviceRepository,
                fixture.connectorRepository, fixture.commandRepository, fixture.transactionRepository,
                fixture.dispatcher, fixture.timeProvider, Duration.ofSeconds(30));

        useCase.execute(fixture.device.deviceCode(), "1", new BigDecimal("50.00"),
                "1234567890123456", "1234567890123456", "ORDER-START");

        verify(fixture.selectedAdapter).encode(any(), any(StartChargingCommand.class));
        verify(fixture.transportSession).send(protocol == DeviceProtocol.OCPP_16
                ? any(TextTransportPayload.class) : any(BinaryTransportPayload.class));
    }

    @ParameterizedTest
    @EnumSource(value = DeviceProtocol.class, names = {"YUN_KUAI_CHONG_150", "OCPP_16"})
    void stopUseCaseRoutesCanonicalCommandToSelectedProtocolAdapter(DeviceProtocol protocol) {
        Fixture fixture = fixture(protocol);
        ChargingTransaction transaction = ChargingTransaction.start(fixture.device.tenantId(),
                fixture.device.id(), fixture.device.deviceCode(), fixture.connector.id(),
                fixture.connector.connectorNo(), protocol, UUID.randomUUID(), "ORDER-STOP", NOW);
        transaction.bindProtocolTransactionId("2400000000000001", NOW);
        when(fixture.transactionRepository.findActiveByDeviceAndConnector(
                fixture.device.id(), fixture.connector.id())).thenReturn(Optional.of(transaction));
        StopChargingUseCase useCase = new StopChargingUseCase(fixture.deviceRepository,
                fixture.connectorRepository, fixture.transactionRepository, fixture.commandRepository,
                fixture.dispatcher, fixture.timeProvider, Duration.ofSeconds(30));

        useCase.execute(fixture.device.deviceCode(), "1");

        verify(fixture.selectedAdapter).encode(any(), any(StopChargingCommand.class));
        verify(fixture.transportSession).send(protocol == DeviceProtocol.OCPP_16
                ? any(TextTransportPayload.class) : any(BinaryTransportPayload.class));
    }

    private Fixture fixture(DeviceProtocol protocol) {
        TimeProvider timeProvider = () -> NOW;
        ChargingDevice device = new ChargingDevice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                protocol == DeviceProtocol.OCPP_16 ? "OCPP-CP-001" : "20231212000010",
                "Test charger", protocol, protocol == DeviceProtocol.OCPP_16 ? "1.6J" : "1.5",
                "ENABLED", NOW, NOW, 0);
        Connector connector = new Connector(UUID.randomUUID(), device.tenantId(), device.stationId(),
                device.id(), 1, "01", "DC", new BigDecimal("120"), "AVAILABLE", NOW, NOW);

        ChargingDeviceRepository deviceRepository = mock(ChargingDeviceRepository.class);
        ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
        DeviceCommandRepository commandRepository = mock(DeviceCommandRepository.class);
        ChargingTransactionRepository transactionRepository = mock(ChargingTransactionRepository.class);
        when(deviceRepository.findByDeviceCode(device.deviceCode())).thenReturn(Optional.of(device));
        when(connectorRepository.findByDeviceIdAndConnectorNo(device.id(), 1)).thenReturn(Optional.of(connector));

        AtomicReference<DeviceCommand> storedCommand = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            storedCommand.set(invocation.getArgument(0));
            return null;
        }).when(commandRepository).save(any(DeviceCommand.class));
        when(commandRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(storedCommand.get()));
        when(commandRepository.saveOrGetActive(any())).thenAnswer(invocation -> {
            DeviceCommand command = invocation.getArgument(0);
            storedCommand.set(command);
            return command;
        });

        TransportSession transportSession = mock(TransportSession.class);
        when(transportSession.id()).thenReturn("session-" + protocol.name());
        when(transportSession.endpointId()).thenReturn(new TransportEndpointId("test-" + protocol.name()));
        when(transportSession.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 9000));
        when(transportSession.localAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 9001));
        when(transportSession.isOpen()).thenReturn(true);
        when(transportSession.send(any())).thenReturn(CompletableFuture.completedFuture(null));
        DeviceSession session = new DeviceSession(transportSession);
        session.beginIdentification();
        session.beginAuthentication(protocol);
        session.activate(new DeviceId(device.deviceCode()), protocol);

        DeviceSessionRegistry sessionRegistry = mock(DeviceSessionRegistry.class);
        when(sessionRegistry.find(session.sessionId())).thenReturn(Optional.of(session));
        DeviceRoute route = new DeviceRoute(new DeviceId(device.deviceCode()), "monolith",
                session.sessionId(), session.connectionEpoch(), protocol, NOW, NOW, NOW.plusSeconds(60));
        DeviceRouteDirectory routeDirectory = mock(DeviceRouteDirectory.class);
        when(routeDirectory.find(new DeviceId(device.deviceCode()))).thenReturn(Optional.of(route));

        YunKuaiChongProtocolAdapter ykcAdapter = mock(YunKuaiChongProtocolAdapter.class);
        Ocpp16ProtocolAdapter ocppAdapter = mock(Ocpp16ProtocolAdapter.class);
        when(ykcAdapter.protocol()).thenReturn(DeviceProtocol.YUN_KUAI_CHONG_150);
        when(ykcAdapter.endpointId()).thenReturn("ykc-test");
        when(ykcAdapter.encode(any(), any())).thenReturn(new BinaryTransportPayload(new byte[]{0x01}));
        when(ocppAdapter.protocol()).thenReturn(DeviceProtocol.OCPP_16);
        when(ocppAdapter.endpointId()).thenReturn("ocpp-test");
        when(ocppAdapter.encode(any(), any())).thenReturn(new TextTransportPayload("[]"));
        ProtocolAdapter selectedAdapter = protocol == DeviceProtocol.OCPP_16 ? ocppAdapter : ykcAdapter;
        ProtocolRegistry protocolRegistry = new ProtocolRegistry(List.of(ykcAdapter, ocppAdapter));
        DeviceCommandDispatcher dispatcher = new DeviceCommandDispatcher(
                new DeviceRouter(routeDirectory, timeProvider), sessionRegistry, protocolRegistry);

        return new Fixture(timeProvider, device, connector, deviceRepository, connectorRepository,
                commandRepository, transactionRepository, dispatcher, selectedAdapter, transportSession);
    }

    private record Fixture(TimeProvider timeProvider, ChargingDevice device, Connector connector,
                           ChargingDeviceRepository deviceRepository, ConnectorRepository connectorRepository,
                           DeviceCommandRepository commandRepository,
                           ChargingTransactionRepository transactionRepository,
                           DeviceCommandDispatcher dispatcher, ProtocolAdapter selectedAdapter,
                           TransportSession transportSession) {
    }
}
