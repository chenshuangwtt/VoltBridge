package com.voltbridge.business.application.device;

import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HandleConnectorStatusUseCase implements DeviceEventHandler<ConnectorStatusEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleConnectorStatusUseCase.class);
    private final ConnectorRuntimeStateStore runtimeStateStore;
    private final DeviceRuntimeMetrics metrics;

    public HandleConnectorStatusUseCase(ConnectorRuntimeStateStore runtimeStateStore,
                                        DeviceRuntimeMetrics metrics) {
        this.runtimeStateStore = runtimeStateStore;
        this.metrics = metrics;
    }

    @Override
    public Class<ConnectorStatusEvent> eventType() {
        return ConnectorStatusEvent.class;
    }

    @Override
    public void handle(ConnectorStatusEvent event) {
        ConnectorRuntimeState previous = runtimeStateStore
                .findConnector(event.deviceId(), event.connectorNo()).orElse(null);
        runtimeStateStore.updateStatus(event.deviceId(), event.connectorNo(), event.status(), event.occurredAt());
        if (previous == null || previous.status() != event.status()) {
            metrics.connectorStatusChanged();
            LOGGER.info("Connector runtime status changed: deviceCode={}, connectorNo={}, status={}",
                    event.deviceId().value(), event.connectorNo(), event.status());
        }
    }
}
