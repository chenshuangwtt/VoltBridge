package com.voltbridge.business.application.device;

import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HandleChargingProgressUseCase implements DeviceEventHandler<ChargingProgressEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleChargingProgressUseCase.class);
    private final ConnectorRuntimeStateStore runtimeStateStore;
    private final DeviceRuntimeMetrics metrics;

    public HandleChargingProgressUseCase(ConnectorRuntimeStateStore runtimeStateStore,
                                         DeviceRuntimeMetrics metrics) {
        this.runtimeStateStore = runtimeStateStore;
        this.metrics = metrics;
    }

    @Override
    public Class<ChargingProgressEvent> eventType() {
        return ChargingProgressEvent.class;
    }

    @Override
    public void handle(ChargingProgressEvent event) {
        ConnectorRuntimeState previous = runtimeStateStore
                .findConnector(event.deviceId(), event.connectorNo()).orElse(null);
        runtimeStateStore.updateStatus(event.deviceId(), event.connectorNo(),
                ConnectorStatus.CHARGING, event.occurredAt());
        runtimeStateStore.recordProgress(event);
        metrics.chargingProgressReceived();
        if (previous == null || previous.status() != ConnectorStatus.CHARGING) {
            metrics.connectorStatusChanged();
        }
        LOGGER.debug("Charging progress updated: deviceCode={}, connectorNo={}, transactionId={}, soc={}, energyKwh={}",
                event.deviceId().value(), event.connectorNo(), event.transactionId(),
                event.stateOfCharge(), event.energyKwh());
    }
}
