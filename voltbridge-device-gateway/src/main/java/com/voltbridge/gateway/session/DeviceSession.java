package com.voltbridge.gateway.session;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.transport.api.TransportSession;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 设备网关对一条真实连接的运行时表示。
 *
 * <p>{@link TransportSession} 表示底层 TCP/WebSocket 连接；本类在其上增加识别、认证、设备绑定、
 * 协议绑定和连接代次。只有认证后的 {@code ACTIVE} 会话可参与下行。由于持有活动网络对象，
 * 它只能存在于本地内存，禁止写入 PostgreSQL 或任何共享路由存储。</p>
 */
public final class DeviceSession {

    private final SessionId sessionId;
    private final TransportSession transportSession;
    private final long connectionEpoch;
    private final AtomicReference<DeviceId> deviceId = new AtomicReference<>();
    private final AtomicReference<DeviceProtocol> protocol = new AtomicReference<>();
    private final AtomicReference<DeviceSessionState> state = new AtomicReference<>(DeviceSessionState.CONNECTED);
    private final AtomicInteger sequence = new AtomicInteger();

    public DeviceSession(TransportSession transportSession) {
        this.sessionId = new SessionId(transportSession.id());
        this.transportSession = transportSession;
        this.connectionEpoch = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public TransportSession transportSession() {
        return transportSession;
    }

    public long connectionEpoch() {
        return connectionEpoch;
    }

    public DeviceSessionState state() {
        return state.get();
    }

    public Optional<DeviceId> deviceId() {
        return Optional.ofNullable(deviceId.get());
    }

    public Optional<DeviceProtocol> protocol() {
        return Optional.ofNullable(protocol.get());
    }

    public void beginIdentification() {
        // 连接建立时只有 endpoint 已知，设备身份必须等待协议首包确认。
        transition(DeviceSessionState.CONNECTED, DeviceSessionState.IDENTIFYING);
    }

    public void beginAuthentication(DeviceProtocol identifiedProtocol) {
        // endpoint 只确定协议；设备仍需经过业务注册信息校验后才能绑定。
        transition(DeviceSessionState.IDENTIFYING, DeviceSessionState.AUTHENTICATING);
        protocol.set(identifiedProtocol);
    }

    public synchronized void activate(DeviceId boundDeviceId, DeviceProtocol boundProtocol) {
        if (state.get() != DeviceSessionState.AUTHENTICATING) {
            throw new IllegalStateException("Expected session state AUTHENTICATING but was " + state.get());
        }
        // 认证成功后一次性绑定设备与协议，随后网关才会发布 DeviceRoute。
        deviceId.set(boundDeviceId);
        protocol.set(boundProtocol);
        state.set(DeviceSessionState.ACTIVE);
    }

    public void beginClosing() {
        DeviceSessionState current = state.get();
        if (current != DeviceSessionState.CLOSED) {
            state.set(DeviceSessionState.CLOSING);
        }
    }

    public void markClosed() {
        state.set(DeviceSessionState.CLOSED);
    }

    public boolean matches(DeviceId expectedDeviceId, DeviceProtocol expectedProtocol, long expectedEpoch) {
        // 三元匹配阻止旧 sessionId/epoch 或错误协议复用当前活动连接。
        return connectionEpoch == expectedEpoch
                && deviceId().filter(expectedDeviceId::equals).isPresent()
                && protocol().filter(expectedProtocol::equals).isPresent()
                && state() == DeviceSessionState.ACTIVE;
    }

    public ProtocolContext protocolContext(DeviceProtocol expectedProtocol) {
        if (state() != DeviceSessionState.ACTIVE || protocol().filter(expectedProtocol::equals).isEmpty()) {
            throw new IllegalStateException("Session is not active for protocol " + expectedProtocol);
        }
        return createProtocolContext(expectedProtocol);
    }

    public ProtocolContext inboundProtocolContext(DeviceProtocol expectedProtocol) {
        DeviceSessionState current = state();
        if ((current != DeviceSessionState.AUTHENTICATING && current != DeviceSessionState.ACTIVE)
                || protocol().filter(expectedProtocol::equals).isEmpty()) {
            throw new IllegalStateException("Session cannot receive frames for protocol " + expectedProtocol);
        }
        return createProtocolContext(expectedProtocol);
    }

    private ProtocolContext createProtocolContext(DeviceProtocol expectedProtocol) {
        return new ProtocolContext(sessionId.value(), transportSession.remoteAddress().toString(),
                expectedProtocol, deviceId(), this::nextSequence, transportSession.attributes());
    }

    private int nextSequence() {
        // 云快充使用 16 位序列号；OCPP 不消费该序列，但共享上下文无需暴露会话实现。
        return sequence.updateAndGet(current -> current == 0xFFFF ? 1 : current + 1);
    }

    private void transition(DeviceSessionState expected, DeviceSessionState target) {
        if (!state.compareAndSet(expected, target)) {
            throw new IllegalStateException("Expected session state " + expected + " but was " + state.get());
        }
    }
}
