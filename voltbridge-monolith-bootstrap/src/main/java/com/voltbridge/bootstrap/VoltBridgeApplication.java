package com.voltbridge.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * VoltBridge 当前唯一的运行时组合根。
 *
 * <p>Spring 从这里扫描并装配 REST、业务应用、设备网关、TCP/WebSocket 传输、
 * 云快充/OCPP 协议和基础设施实现。该类只负责启动单体进程，不承载协议或业务逻辑。</p>
 */
@SpringBootApplication(scanBasePackages = "com.voltbridge")
@EnableScheduling
public class VoltBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoltBridgeApplication.class, args);
    }
}
