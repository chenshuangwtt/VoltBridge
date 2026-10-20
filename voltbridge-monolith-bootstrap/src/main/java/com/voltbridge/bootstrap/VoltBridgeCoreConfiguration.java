package com.voltbridge.bootstrap;

import com.voltbridge.common.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为各层提供统一系统时钟，便于领域状态转换和测试使用同一时间来源。 */
@Configuration
public class VoltBridgeCoreConfiguration {

    @Bean
    TimeProvider timeProvider() {
        return TimeProvider.system();
    }
}
