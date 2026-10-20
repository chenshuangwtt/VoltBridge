package com.voltbridge.device.port;

import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.runtime.ChargingProgressSnapshot;
import com.voltbridge.device.runtime.ConnectorRuntimeState;

import java.time.Instant;
import java.util.Optional;

public interface ConnectorRuntimeStateStore {

    ConnectorRuntimeState updateStatus(DeviceId deviceId, int connectorNo,
                                       ConnectorStatus status, Instant occurredAt);

    ChargingProgressSnapshot recordProgress(ChargingProgressEvent event);

    Optional<ConnectorRuntimeState> findConnector(DeviceId deviceId, int connectorNo);

    Optional<ChargingProgressSnapshot> findProgress(DeviceId deviceId, int connectorNo);
}
