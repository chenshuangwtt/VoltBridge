package com.voltbridge.transport.tcp;

import com.voltbridge.transport.api.TransportConnectionListener;
import com.voltbridge.transport.api.TransportEndpointId;
import com.voltbridge.transport.api.TransportFrame;
import com.voltbridge.transport.api.BinaryTransportPayload;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * 将 Netty Channel 生命周期和已经完成粘拆包的二进制帧转换为 transport-api 回调。
 *
 * <p>本类是 Netty 与平台传输抽象的边界：不识别设备、不解析协议命令，也不执行业务逻辑。</p>
 */
public class TcpChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TcpChannelHandler.class);
    private final TransportEndpointId endpointId;
    private final List<TransportConnectionListener> listeners;
    private TcpTransportSession session;
    private Throwable disconnectCause;

    public TcpChannelHandler(String endpointId, List<TransportConnectionListener> listeners) {
        this.endpointId = new TransportEndpointId(endpointId);
        this.listeners = listeners;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        session = new TcpTransportSession(endpointId, context.channel());
        LOGGER.info("TCP connection accepted: sessionId={}, endpoint={}, remoteAddress={}",
                session.id(), endpointId.value(), session.remoteAddress());
        listeners.forEach(listener -> listener.onConnected(session));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
        // 上游 FrameDecoder 已保证这里是一条完整协议帧；复制后立即脱离 Netty ByteBuf 生命周期。
        byte[] payload = new byte[message.readableBytes()];
        message.readBytes(payload);
        TransportFrame frame = new TransportFrame(session.id(), new BinaryTransportPayload(payload), Instant.now());
        listeners.forEach(listener -> listener.onFrame(session, frame));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (session != null) {
            LOGGER.info("TCP connection closed: sessionId={}, remoteAddress={}",
                    session.id(), session.remoteAddress());
            listeners.forEach(listener -> listener.onDisconnected(session, disconnectCause));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        disconnectCause = cause;
        context.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent) {
            disconnectCause = new ReaderIdleTimeoutException();
            LOGGER.info("TCP connection closed after reader idle timeout: sessionId={}",
                    session == null ? "unknown" : session.id());
            context.close();
        } else {
            context.fireUserEventTriggered(event);
        }
    }
}
