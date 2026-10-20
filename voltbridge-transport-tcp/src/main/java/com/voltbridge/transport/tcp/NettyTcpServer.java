package com.voltbridge.transport.tcp;

import com.voltbridge.transport.api.TransportConnectionListener;
import com.voltbridge.transport.api.TransportServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.net.InetSocketAddress;

/**
 * Netty 实现的 TCP 长连接服务器。
 *
 * <p>由 Spring 生命周期自动启动和优雅停止，只组装通用传输处理器，不包含任何充电协议逻辑。</p>
 */
@Component
public class NettyTcpServer implements TransportServer, SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyTcpServer.class);

    private final TcpServerProperties properties;
    private final List<TransportConnectionListener> listeners;
    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyTcpServer(TcpServerProperties properties, List<TransportConnectionListener> listeners) {
        this.properties = properties;
        this.listeners = listeners;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ConnectionLimitHandler connectionLimitHandler = new ConnectionLimitHandler(properties.getMaxConnections());
        try {
            // 监听器只产出通用 TransportSession/TransportFrame，协议选择留给 device-gateway。
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new TcpChannelInitializer(properties, connectionLimitHandler, listeners))
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .bind(properties.getHost(), properties.getPort())
                    .sync()
                    .channel();
            running = true;
            LOGGER.info("TCP endpoint {} listening on {}:{}", properties.getEndpointId(),
                    properties.getHost(), boundPort());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("TCP server startup was interrupted", exception);
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int boundPort() {
        if (serverChannel == null || !(serverChannel.localAddress() instanceof InetSocketAddress address)) {
            return -1;
        }
        return address.getPort();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // 最后启动、最先停止，确保业务组件和连接监听器始终先于网络入口就绪。
        return Integer.MAX_VALUE;
    }
}
