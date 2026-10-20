package com.voltbridge.device.port;

import com.voltbridge.device.event.DeviceEvent;

/**
 * 网关发布统一设备事实的入站端口，业务处理器只消费标准 {@link DeviceEvent}。
 *
 * <p>协议 DTO、传输帧和活动会话必须在调用该端口之前被转换或剥离。当前实现为进程内同步分发。</p>
 */
public interface DeviceEventPublisher {

    void publish(DeviceEvent event);
}
