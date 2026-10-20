package com.voltbridge.gateway.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网关中少量必须等待业务决定的阻塞边界使用虚拟线程执行。
 *
 * <p>Netty 事件循环只负责收发帧；gRPC 准入调用和同步单体认证不会占用 I/O 线程。</p>
 */
@Configuration
public class GatewayThreadingConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService gatewayAdmissionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
