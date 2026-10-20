package com.voltbridge.transport.tcp;

import com.voltbridge.transport.api.TransportConnectionListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 为每条 TCP 连接组装连接限制、读空闲检测、字节帧限制和生命周期回调处理器。
 */
public class TcpChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final TcpServerProperties properties;
    private final ConnectionLimitHandler connectionLimitHandler;
    private final List<TransportConnectionListener> listeners;

    public TcpChannelInitializer(TcpServerProperties properties,
                                 ConnectionLimitHandler connectionLimitHandler,
                                 List<TransportConnectionListener> listeners) {
        this.properties = properties;
        this.connectionLimitHandler = connectionLimitHandler;
        this.listeners = listeners;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast("connectionLimit", connectionLimitHandler)
                .addLast("readerIdle", new IdleStateHandler(
                        properties.getReaderIdleTimeout().toMillis(), 0, 0, TimeUnit.MILLISECONDS))
                .addLast("frameDecoder", TcpFrameDecoderFactory.create(properties))
                .addLast("channelHandler", new TcpChannelHandler(properties.getEndpointId(), listeners));
    }
}
