package com.voltbridge.gateway.session;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.event.DeviceDisconnectedEvent;
import com.voltbridge.device.model.DeviceConnectionDecision;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceConnectionAuthorizer;
import com.voltbridge.device.port.DeviceTransactionStartService;
import com.voltbridge.gateway.event.DeviceEventDispatcher;
import com.voltbridge.gateway.protocol.ProtocolRegistry;
import com.voltbridge.gateway.routing.DeviceRoute;
import com.voltbridge.gateway.routing.DeviceRouteDirectory;
import com.voltbridge.gateway.routing.GatewayNodeIdentity;
import com.voltbridge.protocol.common.spi.ProtocolAdapter;
import com.voltbridge.protocol.common.spi.ProtocolAuthenticationRequest;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.common.spi.ProtocolTransactionStartRequest;
import com.voltbridge.transport.api.TransportFrame;
import com.voltbridge.transport.api.TransportSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 单体模式下连接进入设备网关后的主编排器。
 *
 * <p>它连接传输回调、协议适配、业务认证、标准事件分发和本地会话路由，但不解析协议字段，
 * 也不实现充电业务规则。TCP 与 WebSocket 共用该主线。</p>
 */
@Component
public class LocalGatewayConnectionCoordinator implements GatewayConnectionCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalGatewayConnectionCoordinator.class);

    private final DeviceSessionRegistry sessionRegistry;
    private final DeviceRouteDirectory routeDirectory;
    private final ProtocolRegistry protocolRegistry;
    private final DeviceConnectionAuthorizer connectionAuthorizer;
    private final DeviceTransactionStartService transactionStartService;
    private final DeviceEventDispatcher eventDispatcher;
    private final TimeProvider timeProvider;
    private final String gatewayNodeId;
    private final String gatewayNodeInstanceId;
    private final Duration routeLease;
    private final Executor admissionExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalGatewayConnectionCoordinator(DeviceSessionRegistry sessionRegistry,
                                             DeviceRouteDirectory routeDirectory,
                                             ProtocolRegistry protocolRegistry,
                                             DeviceConnectionAuthorizer connectionAuthorizer,
                                             DeviceTransactionStartService transactionStartService,
                                             DeviceEventDispatcher eventDispatcher,
                                             TimeProvider timeProvider,
                                             @org.springframework.beans.factory.annotation.Qualifier("gatewayAdmissionExecutor") Executor admissionExecutor,
                                             GatewayNodeIdentity gatewayNodeIdentity,
                                             @Value("${voltbridge.gateway.route-lease:PT10M}") Duration routeLease) {
        this.sessionRegistry = sessionRegistry;
        this.routeDirectory = routeDirectory;
        this.protocolRegistry = protocolRegistry;
        this.connectionAuthorizer = connectionAuthorizer;
        this.transactionStartService = transactionStartService;
        this.eventDispatcher = eventDispatcher;
        this.timeProvider = timeProvider;
        this.admissionExecutor = admissionExecutor;
        this.gatewayNodeId = gatewayNodeIdentity.gatewayNodeId();
        this.gatewayNodeInstanceId = gatewayNodeIdentity.nodeInstanceId();
        this.routeLease = routeLease;
    }

    /** 保留直接组装网关集成测试的构造方式；生产 Spring 装配使用受管虚拟线程执行器。 */
    public LocalGatewayConnectionCoordinator(DeviceSessionRegistry sessionRegistry,
                                             DeviceRouteDirectory routeDirectory,
                                             ProtocolRegistry protocolRegistry,
                                             DeviceConnectionAuthorizer connectionAuthorizer,
                                             DeviceTransactionStartService transactionStartService,
                                             DeviceEventDispatcher eventDispatcher,
                                             TimeProvider timeProvider,
                                             String gatewayNodeId,
                                             Duration routeLease) {
        this(sessionRegistry, routeDirectory, protocolRegistry, connectionAuthorizer, transactionStartService, eventDispatcher, timeProvider,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                new GatewayNodeIdentity(gatewayNodeId, "local"), routeLease);
    }

    /**
     * 兼容既有仅覆盖登录/心跳的集成测试夹具。
     *
     * <p>生产装配不会使用此构造器；若夹具意外发送设备主动开充，会得到明确拒绝而非空指针。</p>
     */
    public LocalGatewayConnectionCoordinator(DeviceSessionRegistry sessionRegistry,
                                             DeviceRouteDirectory routeDirectory,
                                             ProtocolRegistry protocolRegistry,
                                             DeviceConnectionAuthorizer connectionAuthorizer,
                                             DeviceEventDispatcher eventDispatcher,
                                             TimeProvider timeProvider,
                                             String gatewayNodeId,
                                             Duration routeLease) {
        this(sessionRegistry, routeDirectory, protocolRegistry, connectionAuthorizer,
                request -> new com.voltbridge.device.model.DeviceTransactionStartResult(false, 0, null,
                        "DEVICE_TRANSACTION_START_SERVICE_NOT_CONFIGURED"),
                eventDispatcher, timeProvider, gatewayNodeId, routeLease);
    }

    @Override
    public void onConnected(TransportSession transportSession) {
        // TransportSession 是原始连接；先包装为未认证 DeviceSession，尚不发布设备路由。
        DeviceSession session = new DeviceSession(transportSession);
        session.beginIdentification();
        sessionRegistry.register(session);
        LOGGER.info("Device session created: sessionId={}, endpoint={}, remoteAddress={}",
                session.sessionId().value(), transportSession.endpointId().value(),
                transportSession.remoteAddress());
    }

    @Override
    public void onFrame(TransportSession transportSession, TransportFrame frame) {
        SessionId sessionId = new SessionId(transportSession.id());
        DeviceSession session = sessionRegistry.find(sessionId).orElse(null);
        if (session == null) {
            LOGGER.warn("Frame ignored because session is missing: sessionId={}", sessionId.value());
            transportSession.close();
            return;
        }
        try {
            // endpoint 在握手/监听配置中固定，因此可在设备身份未知时先选择正确协议适配器。
            ProtocolAdapter adapter = protocolRegistry.requireEndpoint(transportSession.endpointId().value());
            if (session.state() == DeviceSessionState.IDENTIFYING) {
                session.beginAuthentication(adapter.protocol());
                LOGGER.info("Protocol resolved: sessionId={}, protocol={}", sessionId.value(), adapter.protocol());
            }
            // RAW payload -> protocol DTO/frame -> canonical event/authentication request。
            ProtocolInboundResult result = adapter.decode(
                    session.inboundProtocolContext(adapter.protocol()), frame.payload());
            refreshRouteLiveness(session);
            // 先同步完成业务事件，再发送协议即时应答；持久化失败会阻止成功 ACK 提前写出。
            result.events().forEach(eventDispatcher::dispatch);
            result.immediateReplies().forEach(reply -> transportSession.send(reply)
                    .whenComplete((ignored, error) -> {
                        if (error == null) {
                            LOGGER.debug("Immediate protocol reply sent: sessionId={}, payloadType={}",
                                    session.sessionId().value(), reply.getClass().getSimpleName());
                        } else {
                            LOGGER.warn("Immediate protocol reply failed: sessionId={}, reason={}",
                                    session.sessionId().value(), error.getMessage());
                        }
                    }));
            result.authenticationRequest().ifPresent(request -> authenticate(session, request));
            result.transactionStartRequest().ifPresent(request -> startTransaction(session, request));
        } catch (RuntimeException exception) {
            LOGGER.warn("Frame processing failed: sessionId={}, reason={}",
                    sessionId.value(), exception.getMessage());
            transportSession.close();
        }
    }

    private void refreshRouteLiveness(DeviceSession session) {
        if (session.state() != DeviceSessionState.ACTIVE) {
            return;
        }
        DeviceId deviceId = session.deviceId().orElse(null);
        if (deviceId == null) {
            return;
        }
        Instant lastSeenAt = timeProvider.now();
        routeDirectory.touch(deviceId, gatewayNodeId, gatewayNodeInstanceId, session.sessionId(), session.connectionEpoch(),
                lastSeenAt, lastSeenAt.plus(routeLease));
    }

    @Override
    public void onDisconnected(TransportSession transportSession, Throwable cause) {
        SessionId sessionId = new SessionId(transportSession.id());
        sessionRegistry.remove(sessionId).ifPresent(session -> cleanup(session, cause));
    }

    private void authenticate(DeviceSession session, ProtocolAuthenticationRequest request) {
        if (session.state() != DeviceSessionState.AUTHENTICATING) {
            throw new IllegalStateException("Authentication request received for session state " + session.state());
        }
        // 协议层只提供标准连接事件；同步准入可能通过 gRPC 访问业务服务，不能阻塞 Netty I/O 线程。
        DeviceConnectedEvent event = request.event();
        CompletableFuture.supplyAsync(() -> connectionAuthorizer.authorize(event), admissionExecutor)
                .whenComplete((decision, error) -> {
                    if (error != null) {
                        LOGGER.warn("Business login validation failed: deviceCode={}, sessionId={}, reason={}",
                                event.deviceId().value(), event.sessionId(), error.getMessage());
                        sendAuthenticationReply(session, request, false, true);
                        return;
                    }
                    completeAuthentication(session, request, event, decision);
                });
    }

    private void startTransaction(DeviceSession session, ProtocolTransactionStartRequest request) {
        // 0x31 等协议帧要求即时回包，但其业务准入可跨服务；使用受管执行器隔离 I/O 线程。
        CompletableFuture.supplyAsync(() -> transactionStartService.start(request.request()), admissionExecutor)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOGGER.warn("Device initiated transaction admission failed: deviceCode={}, sessionId={}, reason={}",
                                request.request().deviceId().value(), session.sessionId().value(), error.getMessage());
                        return;
                    }
                    session.transportSession().send(request.responder().encode(result)).whenComplete((ignored, sendError) -> {
                        if (sendError == null) {
                            LOGGER.info("Device initiated transaction reply sent: deviceCode={}, sessionId={}, accepted={}",
                                    request.request().deviceId().value(), session.sessionId().value(), result.accepted());
                        } else {
                            LOGGER.warn("Device initiated transaction reply failed: deviceCode={}, sessionId={}, reason={}",
                                    request.request().deviceId().value(), session.sessionId().value(), sendError.getMessage());
                        }
                    });
                });
    }

    private void completeAuthentication(DeviceSession session, ProtocolAuthenticationRequest request,
                                        DeviceConnectedEvent event, DeviceConnectionDecision decision) {
        LOGGER.info("Business login validation completed: deviceCode={}, sessionId={}, accepted={}, reason={}",
                event.deviceId().value(), event.sessionId(), decision.accepted(), decision.reason());
        if (!decision.accepted()) {
            sendAuthenticationReply(session, request, false, true);
            return;
        }

        // 认证成功后才绑定 DeviceSession 并发布 deviceCode -> sessionId 路由。
        Instant authenticatedAt = timeProvider.now();
        session.activate(event.deviceId(), event.protocol());
        DeviceRoute route = new DeviceRoute(event.deviceId(), gatewayNodeId, gatewayNodeInstanceId, session.sessionId(),
                session.connectionEpoch(), event.protocol(), authenticatedAt,
                authenticatedAt, authenticatedAt.plus(routeLease));
        // 新会话完成业务准入后才可原子声明全局权威路由；旧会话无法与此写入交错删除。
        DeviceRoute previousRoute = routeDirectory.claim(route);
        LOGGER.info("Device route claimed: deviceCode={}, gatewayNodeId={}, nodeInstanceId={}, sessionId={}, epoch={}",
                event.deviceId().value(), gatewayNodeId, gatewayNodeInstanceId, session.sessionId().value(), session.connectionEpoch());

        try {
            // 先让业务在线状态生效，再回复协议成功；失败时撤销刚发布的路由。
            eventDispatcher.dispatch(event);
            LOGGER.info("DeviceConnectedEvent emitted: deviceCode={}, sessionId={}",
                    event.deviceId().value(), event.sessionId());
        } catch (RuntimeException exception) {
            routeDirectory.remove(event.deviceId(), gatewayNodeId, gatewayNodeInstanceId,
                    session.sessionId(), session.connectionEpoch());
            LOGGER.error("Device connected event failed: deviceCode={}, sessionId={}",
                    event.deviceId().value(), event.sessionId(), exception);
            sendAuthenticationReply(session, request, false, true);
            return;
        }

        sendAuthenticationReply(session, request, true, false);
        closeReplacedSession(previousRoute, route);
    }

    private void sendAuthenticationReply(DeviceSession session, ProtocolAuthenticationRequest request,
                                         boolean accepted, boolean closeAfterSend) {
        var reply = request.responder().encode(accepted);
        session.transportSession().send(reply).whenComplete((ignored, error) -> {
            if (error == null) {
                LOGGER.info("Login ACK sent: sessionId={}, accepted={}", session.sessionId().value(), accepted);
            } else {
                LOGGER.warn("Login ACK send failed: sessionId={}, reason={}",
                        session.sessionId().value(), error.getMessage());
            }
            if (closeAfterSend || error != null) {
                session.transportSession().close();
            }
        });
    }

    private void closeReplacedSession(DeviceRoute previousRoute, DeviceRoute currentRoute) {
        if (previousRoute == null || previousRoute.matches(currentRoute.sessionId(), currentRoute.connectionEpoch())) {
            return;
        }
        // 新登录先成为权威路由，再关闭旧连接；旧连接清理时 epoch 校验不会误删新路由。
        sessionRegistry.find(previousRoute.sessionId()).ifPresent(previousSession -> {
            LOGGER.info("Replacing duplicate device session: deviceCode={}, oldSessionId={}, newSessionId={}",
                    currentRoute.deviceId().value(), previousRoute.sessionId().value(), currentRoute.sessionId().value());
            previousSession.transportSession().close();
        });
    }

    private void cleanup(DeviceSession session, Throwable cause) {
        session.beginClosing();
        DeviceId deviceId = session.deviceId().orElse(null);
        DeviceProtocol protocol = session.protocol().orElse(DeviceProtocol.UNKNOWN);
        // 只有成功删除当前权威路由的连接才能发布离线事件，旧连接断开不会覆盖新在线状态。
        boolean activeRouteRemoved = deviceId != null
                && routeDirectory.remove(deviceId, gatewayNodeId, gatewayNodeInstanceId,
                session.sessionId(), session.connectionEpoch());
        session.markClosed();
        String reason = cause == null ? "connection_closed" : cause.getClass().getSimpleName();
        LOGGER.info("Device session removed: gatewayNodeId={}, nodeInstanceId={}, sessionId={}, deviceCode={}, activeRouteRemoved={}, reason={}",
                gatewayNodeId, gatewayNodeInstanceId,
                session.sessionId().value(), deviceId == null ? "unknown" : deviceId.value(),
                activeRouteRemoved, reason);
        if (deviceId != null && activeRouteRemoved) {
            eventDispatcher.dispatch(new DeviceDisconnectedEvent(UUID.randomUUID(), deviceId, protocol,
                    session.sessionId().value(), reason, timeProvider.now()));
        }
    }
}
