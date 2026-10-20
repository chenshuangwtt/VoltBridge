package com.voltbridge.business.application.device;

import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HandleHeartbeatUseCase implements DeviceEventHandler<HeartbeatEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleHeartbeatUseCase.class);
    private final DeviceRuntimeStateStore runtimeStateStore;
    private final DeviceRuntimeMetrics metrics;

    public HandleHeartbeatUseCase(DeviceRuntimeStateStore runtimeStateStore,
                                  DeviceRuntimeMetrics metrics) {
        this.runtimeStateStore = runtimeStateStore;
        this.metrics = metrics;
    }

    @Override
    public Class<HeartbeatEvent> eventType() {
        return HeartbeatEvent.class;
    }

    @Override
    public void handle(HeartbeatEvent event) {
        metrics.heartbeatReceived();
        if (runtimeStateStore.refreshLastSeen(event.deviceId(), event.sessionId(), event.occurredAt()).isEmpty()) {
            LOGGER.warn("Heartbeat ignored for inactive session: deviceCode={}, sessionId={}",
                    event.deviceId().value(), event.sessionId());
        }
    }
}
