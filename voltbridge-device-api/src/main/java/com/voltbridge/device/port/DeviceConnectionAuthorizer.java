package com.voltbridge.device.port;

import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.model.DeviceConnectionDecision;

/**
 * 需要业务确认的设备登录入站端口。
 *
 * <p>它与事件发布端口分离，因为登录 ACK 必须等待明确的同步认证结果。</p>
 */
@FunctionalInterface
public interface DeviceConnectionAuthorizer {

    DeviceConnectionDecision authorize(DeviceConnectedEvent event);
}
