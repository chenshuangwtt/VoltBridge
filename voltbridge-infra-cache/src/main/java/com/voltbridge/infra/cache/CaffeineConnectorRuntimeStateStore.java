package com.voltbridge.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.runtime.ChargingProgressSnapshot;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Repository
public class CaffeineConnectorRuntimeStateStore implements ConnectorRuntimeStateStore {

    private final Cache<ConnectorKey, ConnectorRuntimeState> connectorStates = Caffeine.newBuilder()
            .maximumSize(500_000)
            .expireAfterAccess(Duration.ofDays(1))
            .build();
    private final Cache<ConnectorKey, ChargingProgressSnapshot> progressStates = Caffeine.newBuilder()
            .maximumSize(500_000)
            .expireAfterAccess(Duration.ofHours(6))
            .build();

    @Override
    public ConnectorRuntimeState updateStatus(DeviceId deviceId, int connectorNo,
                                              ConnectorStatus status, Instant occurredAt) {
        ConnectorKey key = new ConnectorKey(deviceId, connectorNo);
        return connectorStates.asMap().compute(key, (ignored, current) -> {
            Instant changedAt = current == null || current.status() != status
                    ? occurredAt : current.changedAt();
            return new ConnectorRuntimeState(deviceId, connectorNo, status, changedAt, occurredAt);
        });
    }

    @Override
    public ChargingProgressSnapshot recordProgress(ChargingProgressEvent event) {
        ChargingProgressSnapshot snapshot = new ChargingProgressSnapshot(event.deviceId(), event.connectorNo(),
                event.transactionId(), event.outputVoltage(), event.outputCurrent(), event.outputPowerKw(),
                event.stateOfCharge(), event.energyKwh(), event.chargingDuration(), event.chargingAmount(),
                event.additionalInfo(), event.occurredAt());
        progressStates.put(new ConnectorKey(event.deviceId(), event.connectorNo()), snapshot);
        return snapshot;
    }

    @Override
    public Optional<ConnectorRuntimeState> findConnector(DeviceId deviceId, int connectorNo) {
        return Optional.ofNullable(connectorStates.getIfPresent(new ConnectorKey(deviceId, connectorNo)));
    }

    @Override
    public Optional<ChargingProgressSnapshot> findProgress(DeviceId deviceId, int connectorNo) {
        return Optional.ofNullable(progressStates.getIfPresent(new ConnectorKey(deviceId, connectorNo)));
    }

    private record ConnectorKey(DeviceId deviceId, int connectorNo) {
    }
}
