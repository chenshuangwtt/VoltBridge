package com.voltbridge.transport.tcp;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在接受连接时执行进程级并发连接数保护。
 */
@ChannelHandler.Sharable
public class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<Boolean> COUNTED = AttributeKey.valueOf("voltbridge.connection.counted");

    private final int maxConnections;
    private final AtomicInteger activeConnections = new AtomicInteger();

    public ConnectionLimitHandler(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (activeConnections.incrementAndGet() > maxConnections) {
            activeConnections.decrementAndGet();
            context.close();
            return;
        }
        context.channel().attr(COUNTED).set(true);
        super.channelActive(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (Boolean.TRUE.equals(context.channel().attr(COUNTED).getAndSet(false))) {
            activeConnections.decrementAndGet();
        }
        super.channelInactive(context);
    }
}
