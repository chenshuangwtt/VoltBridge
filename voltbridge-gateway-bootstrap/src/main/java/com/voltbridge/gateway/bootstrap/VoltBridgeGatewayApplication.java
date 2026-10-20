package com.voltbridge.gateway.bootstrap;

import com.voltbridge.common.time.TimeProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Gateway Service 的组合根：本地会话、TCP/WebSocket、协议适配、Kafka 发布和 gRPC 命令入口。
 *
 * <p>该进程不依赖业务持久化模块；在线路由及活跃网络连接仅保存在本地内存。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.voltbridge.gateway", "com.voltbridge.transport", "com.voltbridge.protocol",
        "com.voltbridge.infra.cache", "com.voltbridge.infra.messaging.kafka",
        "com.voltbridge.infra.rpc.grpc", "com.voltbridge.infra.observability"})
@EnableScheduling
public class VoltBridgeGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoltBridgeGatewayApplication.class, args);
    }

    @Bean
    TimeProvider timeProvider() {
        return TimeProvider.system();
    }
}
