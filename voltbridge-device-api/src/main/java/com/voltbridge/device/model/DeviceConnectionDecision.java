package com.voltbridge.device.model;

/**
 * 业务应用对设备连接认证请求的同步决定。
 */
public record DeviceConnectionDecision(boolean accepted, String reason) {

    public static DeviceConnectionDecision accept() {
        return new DeviceConnectionDecision(true, "accepted");
    }

    public static DeviceConnectionDecision reject(String reason) {
        return new DeviceConnectionDecision(false, reason);
    }
}
