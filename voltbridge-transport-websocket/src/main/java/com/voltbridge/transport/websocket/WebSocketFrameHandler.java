package com.voltbridge.transport.websocket;

import com.voltbridge.transport.api.TextTransportPayload;
import com.voltbridge.transport.api.TransportConnectionListener;
import com.voltbridge.transport.api.TransportFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * 把 WebSocket 文本帧和连接生命周期转换为 transport-api 回调。
 *
 * <p>OCPP 端点只向上游暴露文本负载；Ping/Pong 和 Close 属于传输控制帧，不进入协议层。</p>
 */
final class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketFrameHandler.class);
    private final WebSocketTransportSession session;
    private final WebSocketServerHandshaker handshaker;
    private final List<TransportConnectionListener> listeners;
    private Throwable disconnectCause;

    WebSocketFrameHandler(WebSocketTransportSession session, WebSocketServerHandshaker handshaker,
                          List<TransportConnectionListener> listeners) {
        this.session = session;
        this.handshaker = handshaker;
        this.listeners = listeners;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame text) {
            // 文本内容在这里仍是原始协议负载，JSON 数组解析由 OCPP 适配器完成。
            TransportFrame transportFrame = new TransportFrame(session.id(),
                    new TextTransportPayload(text.text()), Instant.now());
            listeners.forEach(listener -> listener.onFrame(session, transportFrame));
        } else if (frame instanceof PingWebSocketFrame ping) {
            context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame close) {
            handshaker.close(context.channel(), close.retain());
        } else if (frame instanceof BinaryWebSocketFrame) {
            disconnectCause = new IllegalArgumentException("OCPP WebSocket endpoint accepts text frames only");
            context.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        LOGGER.info("WebSocket connection closed: sessionId={}, deviceCode={}",
                session.id(), session.attributes().get(WebSocketTransportSession.DEVICE_CODE_ATTRIBUTE));
        listeners.forEach(listener -> listener.onDisconnected(session, disconnectCause));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        disconnectCause = cause;
        context.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent) {
            disconnectCause = new IllegalStateException("WebSocket reader idle timeout");
            context.close();
        } else {
            context.fireUserEventTriggered(event);
        }
    }
}
