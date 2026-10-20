package com.voltbridge.gateway.event;

import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.port.DeviceEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 设备网关到业务事件端口的薄边界。
 *
 * <p>协议适配器已经在调用前消除了线协议差异；本类不得重新按协议分支或处理业务状态。</p>
 */
@Component
public class DeviceEventDispatcher {

    private final DeviceEventPublisher eventPublisher;

    public DeviceEventDispatcher(DeviceEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void dispatch(DeviceEvent event) {
        eventPublisher.publish(event);
    }
}
