package com.voltbridge.business.bootstrap;

import com.voltbridge.common.time.TimeProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Business Service 的组合根：HTTP、业务用例、PostgreSQL、Kafka 消费和 Gateway gRPC 客户端。
 *
 * <p>只扫描业务侧及其基础设施包，避免把网关连接、协议和传输实现意外装入业务进程。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.voltbridge.business", "com.voltbridge.infra.persistence", "com.voltbridge.infra.cache",
        "com.voltbridge.infra.messaging.memory", "com.voltbridge.infra.messaging.kafka",
        "com.voltbridge.infra.rpc.grpc", "com.voltbridge.infra.observability"})
public class VoltBridgeBusinessApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoltBridgeBusinessApplication.class, args);
    }

    @Bean
    TimeProvider timeProvider() {
        return TimeProvider.system();
    }
}
