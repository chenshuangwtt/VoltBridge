package com.voltbridge.transport.websocket;

import com.voltbridge.transport.api.TextTransportPayload;
import com.voltbridge.transport.api.TransportEndpointId;
import com.voltbridge.transport.api.TransportPayload;
import com.voltbridge.transport.api.TransportSession;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 对已完成握手的 Netty WebSocket Channel 的传输层封装。 */
public final class WebSocketTransportSession implements TransportSession {

    public static final String DEVICE_CODE_ATTRIBUTE = "deviceCode";

    private final TransportEndpointId endpointId;
    private final Channel channel;
    private final Map<String, String> attributes;

    public WebSocketTransportSession(TransportEndpointId endpointId, Channel channel, String deviceCode) {
        this.endpointId = endpointId;
        this.channel = channel;
        this.attributes = Map.of(DEVICE_CODE_ATTRIBUTE, deviceCode);
    }

    @Override public String id() { return channel.id().asLongText(); }
    @Override public TransportEndpointId endpointId() { return endpointId; }
    @Override public SocketAddress remoteAddress() { return channel.remoteAddress(); }
    @Override public SocketAddress localAddress() { return channel.localAddress(); }
    @Override public boolean isOpen() { return channel.isActive(); }
    @Override public Map<String, String> attributes() { return attributes; }

    @Override
    public CompletionStage<Void> send(TransportPayload payload) {
        if (!(payload instanceof TextTransportPayload text)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("WebSocket transport only supports text payloads"));
        }
        return future(channel.writeAndFlush(new TextWebSocketFrame(text.value())));
    }

    @Override
    public CompletionStage<Void> close() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        channel.writeAndFlush(new CloseWebSocketFrame()).addListener(write ->
                channel.close().addListener(close -> {
                    if (close.isSuccess()) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(close.cause());
                    }
                }));
        return result;
    }

    private CompletionStage<Void> future(io.netty.channel.ChannelFuture channelFuture) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        channelFuture.addListener(completed -> {
            if (completed.isSuccess()) {
                result.complete(null);
            } else {
                result.completeExceptionally(completed.cause());
            }
        });
        return result;
    }
}
