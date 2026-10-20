package com.voltbridge.transport.api;

/**
 * 可启动和停止的传输监听器边界，当前由 TCP 和 WebSocket 服务器实现。
 *
 * <p>实现负责监听端口和连接生命周期，不选择设备协议，也不调用业务用例。</p>
 */
public interface TransportServer {

    void start();

    void stop();

    boolean isRunning();
}
