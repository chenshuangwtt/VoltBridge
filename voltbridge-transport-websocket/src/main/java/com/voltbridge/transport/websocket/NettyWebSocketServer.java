package com.voltbridge.transport.websocket;

import com.voltbridge.transport.api.TransportConnectionListener;
import com.voltbridge.transport.api.TransportServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 独立于 Spring MVC 的 Netty WebSocket 服务器，可与 TCP 监听器运行在同一 JVM。
 *
 * <p>服务器只负责 HTTP 升级和 WebSocket 帧传输；OCPP JSON 解析由协议适配器负责。</p>
 */
@Component
public class NettyWebSocketServer implements TransportServer, SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocketServer.class);
    private final WebSocketServerProperties properties;
    private final List<TransportConnectionListener> listeners;
    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyWebSocketServer(WebSocketServerProperties properties,
                                List<TransportConnectionListener> listeners) {
        this.properties = properties;
        this.listeners = listeners;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            serverChannel = new ServerBootstrap().group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            // 先完成 HTTP 聚合与空闲检测，握手成功后再动态安装 WebSocket 帧处理器。
                            channel.pipeline().addLast(new HttpServerCodec());
                            channel.pipeline().addLast(new HttpObjectAggregator(properties.getMaxContentLength()));
                            channel.pipeline().addLast(new IdleStateHandler(
                                    properties.getIdleTimeout().toMillis(), 0, 0, TimeUnit.MILLISECONDS));
                            channel.pipeline().addLast(new WebSocketHandshakeHandler(properties, listeners));
                        }
                    }).bind(properties.getHost(), properties.getPort()).sync().channel();
            running = true;
            LOGGER.info("WebSocket endpoint {} listening on {}:{}{} /{{deviceCode}}",
                    properties.getEndpointId(), properties.getHost(), boundPort(), properties.getPath());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("WebSocket server startup was interrupted", exception);
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        if (serverChannel != null) { serverChannel.close().syncUninterruptibly(); serverChannel = null; }
        if (workerGroup != null) { workerGroup.shutdownGracefully().syncUninterruptibly(); workerGroup = null; }
        if (bossGroup != null) { bossGroup.shutdownGracefully().syncUninterruptibly(); bossGroup = null; }
        running = false;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    /** 最后启动、最先停止，避免网络请求进入尚未就绪或已经销毁的业务组件。 */
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    public int boundPort() {
        return serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress address
                ? address.getPort() : -1;
    }
}
