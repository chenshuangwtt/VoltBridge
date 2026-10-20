package com.voltbridge.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 使用真实 PostgreSQL 验证完整 Spring 容器装配，默认跳过，避免普通构建依赖本地数据库。
 */
@EnabledIfEnvironmentVariable(named = "VOLTBRIDGE_RUN_DB_TEST", matches = "true")
@SpringBootTest(properties = {
        "server.port=0",
        "voltbridge.transport.tcp.port=0"
})
class VoltBridgeApplicationSmokeTest {

    @Test
    void startsWithPostgreSql() {
    }
}
