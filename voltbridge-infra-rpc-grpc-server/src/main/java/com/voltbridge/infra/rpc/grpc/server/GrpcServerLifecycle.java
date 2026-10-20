package com.voltbridge.infra.rpc.grpc.server;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** Spring 生命周期托管的轻量 gRPC server，服务实现由各启动模块按部署模式装配。 */
@Component
@ConditionalOnProperty(name = "voltbridge.grpc.server-enabled", havingValue = "true")
public class GrpcServerLifecycle implements SmartLifecycle {

    private final int port;
    private final List<BindableService> services;
    private volatile Server server;

    public GrpcServerLifecycle(@Value("${voltbridge.grpc.server-port:9090}") int port, List<BindableService> services) {
        this.port = port;
        this.services = services;
    }

    @Override
    public void start() {
        try {
            NettyServerBuilder builder = NettyServerBuilder.forPort(port);
            services.forEach(builder::addService);
            server = builder.build().start();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start gRPC server on port " + port, exception);
        }
    }

    @Override public void stop() { if (server != null) server.shutdownNow(); }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return 0; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
}
