package com.voltbridge.device.port;

import com.voltbridge.device.event.DeviceEvent;

/**
 * 业务应用处理统一设备事件的订阅端口。
 */
@FunctionalInterface
public interface DeviceEventHandler<E extends DeviceEvent> {

    @SuppressWarnings("unchecked")
    default Class<E> eventType() {
        return (Class<E>) DeviceEvent.class;
    }

    void handle(E event);
}
