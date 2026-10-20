package com.voltbridge.device.runtime;

import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;

import java.time.Instant;

public record ConnectorRuntimeState(DeviceId deviceId, int connectorNo, ConnectorStatus status,
                                    Instant changedAt, Instant lastReportedAt) {
}
