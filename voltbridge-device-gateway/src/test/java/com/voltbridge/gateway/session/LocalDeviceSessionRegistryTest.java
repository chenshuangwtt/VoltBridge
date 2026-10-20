package com.voltbridge.gateway.session;

import com.voltbridge.transport.api.TransportEndpointId;
import com.voltbridge.transport.api.TransportSession;
import com.voltbridge.transport.api.TransportPayload;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDeviceSessionRegistryTest {

    @Test
    void registersFindsAndRemovesSession() {
        LocalDeviceSessionRegistry registry = new LocalDeviceSessionRegistry();
        DeviceSession session = new DeviceSession(new StubTransportSession("session-1"));

        registry.register(session);

        assertThat(registry.find(session.sessionId())).containsSame(session);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.remove(session.sessionId())).containsSame(session);
        assertThat(registry.find(session.sessionId())).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void rejectsDuplicateSessionId() {
        LocalDeviceSessionRegistry registry = new LocalDeviceSessionRegistry();
        registry.register(new DeviceSession(new StubTransportSession("session-1")));

        assertThatThrownBy(() -> registry.register(new DeviceSession(new StubTransportSession("session-1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    private record StubTransportSession(String id) implements TransportSession {

        @Override
        public TransportEndpointId endpointId() {
            return new TransportEndpointId("test");
        }

        @Override
        public SocketAddress remoteAddress() {
            return new InetSocketAddress("127.0.0.1", 20000);
        }

        @Override
        public SocketAddress localAddress() {
            return new InetSocketAddress("127.0.0.1", 9000);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public CompletionStage<Void> send(TransportPayload payload) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
