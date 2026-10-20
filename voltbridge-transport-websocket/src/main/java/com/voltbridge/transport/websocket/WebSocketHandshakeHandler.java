package com.voltbridge.transport.websocket;

import com.voltbridge.transport.api.TransportConnectionListener;
import com.voltbridge.transport.api.TransportEndpointId;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 校验 URL 中的设备标识和 {@code ocpp1.6} 子协议后完成 WebSocket 升级。
 *
 * <p>URL 设备号只是连接期候选身份，真正激活设备路由仍需 BootNotification 通过业务鉴权。</p>
 */
final class WebSocketHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final String OCPP_16_SUBPROTOCOL = "ocpp1.6";
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketHandshakeHandler.class);

    private final WebSocketServerProperties properties;
    private final List<TransportConnectionListener> listeners;

    WebSocketHandshakeHandler(WebSocketServerProperties properties,
                              List<TransportConnectionListener> listeners) {
        this.properties = properties;
        this.listeners = listeners;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        // OCPP 1.6-J 以 /{deviceCode} 和子协议共同限定连接入口，任一不匹配都拒绝升级。
        String deviceCode = deviceCode(request.uri());
        if (deviceCode == null) {
            reject(context, request, HttpResponseStatus.NOT_FOUND, "Expected " + properties.getPath() + "/{deviceCode}");
            return;
        }
        if (!requestsOcpp16(request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL))) {
            reject(context, request, HttpResponseStatus.BAD_REQUEST, "Required WebSocket subprotocol: ocpp1.6");
            return;
        }
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                websocketLocation(request), OCPP_16_SUBPROTOCOL, true, properties.getMaxContentLength());
        WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
        if (handshaker == null) {
            reject(context, request, HttpResponseStatus.BAD_REQUEST, "Unsupported WebSocket version or subprotocol");
            return;
        }

        handshaker.handshake(context.channel(), request).addListener(result -> {
            if (!result.isSuccess()) {
                context.close();
                return;
            }
            if (!OCPP_16_SUBPROTOCOL.equals(handshaker.selectedSubprotocol())) {
                context.close();
                return;
            }
            WebSocketTransportSession session = new WebSocketTransportSession(
                    new TransportEndpointId(properties.getEndpointId()), context.channel(), deviceCode);
            // 升级完成后替换 HTTP 握手处理器，连接生命周期自此进入 transport-api。
            context.pipeline().addLast(new WebSocketFrameHandler(session, handshaker, listeners));
            context.pipeline().remove(this);
            LOGGER.info("WebSocket handshake accepted: sessionId={}, deviceCode={}, subprotocol={}",
                    session.id(), deviceCode, handshaker.selectedSubprotocol());
            listeners.forEach(listener -> listener.onConnected(session));
        });
    }

    private String deviceCode(String uri) {
        String path = new QueryStringDecoder(uri).path();
        String prefix = properties.getPath().endsWith("/")
                ? properties.getPath() : properties.getPath() + "/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) return null;
        String value = path.substring(prefix.length());
        return value.contains("/") || value.isBlank() ? null : value;
    }

    private boolean requestsOcpp16(String header) {
        return header != null && Arrays.stream(header.split(","))
                .map(String::trim).anyMatch(OCPP_16_SUBPROTOCOL::equals);
    }

    private String websocketLocation(FullHttpRequest request) {
        return "ws://" + request.headers().get(HttpHeaderNames.HOST) + request.uri();
    }

    private void reject(ChannelHandlerContext context, FullHttpRequest request,
                        HttpResponseStatus status, String reason) {
        byte[] body = reason.getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                context.alloc().buffer(body.length).writeBytes(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        HttpUtil.setKeepAlive(response, false);
        context.writeAndFlush(response).addListener(result -> context.close());
        LOGGER.warn("WebSocket handshake rejected: remoteAddress={}, reason={}",
                context.channel().remoteAddress(), reason);
    }
}
