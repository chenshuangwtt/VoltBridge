package com.voltbridge.business.application.device;

import com.voltbridge.device.event.DeviceDisconnectedEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HandleDeviceDisconnectedUseCase implements DeviceEventHandler<DeviceDisconnectedEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleDeviceDisconnectedUseCase.class);
    private final DeviceRuntimeStateStore runtimeStateStore;
    private final DeviceRuntimeMetrics metrics;

    public HandleDeviceDisconnectedUseCase(DeviceRuntimeStateStore runtimeStateStore,
                                           DeviceRuntimeMetrics metrics) {
        this.runtimeStateStore = runtimeStateStore;
        this.metrics = metrics;
    }

    @Override
    public Class<DeviceDisconnectedEvent> eventType() {
        return DeviceDisconnectedEvent.class;
    }

    @Override
    public void handle(DeviceDisconnectedEvent event) {
        if (runtimeStateStore.markOffline(event.deviceId(), event.sessionId(), event.occurredAt()).isEmpty()) {
            return;
        }
        if ("ReaderIdleTimeoutException".equals(event.reason())) {
            metrics.offlineTimeout();
        }
        LOGGER.info("Device runtime state changed: deviceCode={}, sessionId={}, onlineStatus=OFFLINE, reason={}",
                event.deviceId().value(), event.sessionId(), event.reason());
    }
}
