package com.voltbridge.transport.tcp;

import com.voltbridge.transport.api.TransportSession;
import com.voltbridge.transport.api.TransportEndpointId;
import com.voltbridge.transport.api.TransportPayload;
import com.voltbridge.transport.api.BinaryTransportPayload;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 对 Netty Channel 的传输层封装，屏蔽上层对 Netty API 的直接依赖。
 */
public class TcpTransportSession implements TransportSession {

    private final TransportEndpointId endpointId;
    private final Channel channel;

    public TcpTransportSession(TransportEndpointId endpointId, Channel channel) {
        this.endpointId = endpointId;
        this.channel = channel;
    }

    @Override
    public String id() {
        return channel.id().asLongText();
    }

    @Override
    public TransportEndpointId endpointId() {
        return endpointId;
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    @Override
    public boolean isOpen() {
        return channel.isActive();
    }

    @Override
    public CompletionStage<Void> send(TransportPayload payload) {
        if (!(payload instanceof BinaryTransportPayload binary)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("TCP transport only supports binary payloads"));
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        channel.writeAndFlush(Unpooled.wrappedBuffer(binary.value())).addListener(result -> {
            if (result.isSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    @Override
    public CompletionStage<Void> close() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        channel.close().addListener(result -> {
            if (result.isSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }
}
