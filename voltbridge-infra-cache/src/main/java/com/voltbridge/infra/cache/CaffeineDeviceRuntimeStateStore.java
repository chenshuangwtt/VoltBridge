package com.voltbridge.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.port.DeviceRuntimeMetrics;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import com.voltbridge.device.runtime.DeviceRuntimeState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class CaffeineDeviceRuntimeStateStore implements DeviceRuntimeStateStore {

    private final Cache<DeviceId, DeviceRuntimeState> states = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofDays(1))
            .build();
    private final DeviceRuntimeMetrics metrics;

    public CaffeineDeviceRuntimeStateStore() {
        this(DeviceRuntimeMetrics.NOOP);
    }

    @Autowired
    public CaffeineDeviceRuntimeStateStore(DeviceRuntimeMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public DeviceRuntimeState markOnline(DeviceId deviceId, String sessionId, Instant occurredAt) {
        DeviceRuntimeState state = new DeviceRuntimeState(deviceId, DeviceOnlineStatus.ONLINE,
                sessionId, occurredAt, occurredAt, null);
        states.put(deviceId, state);
        metrics.connectedDevices(onlineCount());
        return state;
    }

    @Override
    public Optional<DeviceRuntimeState> refreshLastSeen(DeviceId deviceId, String sessionId, Instant occurredAt) {
        AtomicReference<DeviceRuntimeState> updated = new AtomicReference<>();
        states.asMap().computeIfPresent(deviceId, (ignored, current) -> {
            if (current.onlineStatus() != DeviceOnlineStatus.ONLINE || !current.sessionId().equals(sessionId)) {
                return current;
            }
            DeviceRuntimeState next = new DeviceRuntimeState(deviceId, DeviceOnlineStatus.ONLINE,
                    sessionId, current.connectedAt(), occurredAt, null);
            updated.set(next);
            return next;
        });
        return Optional.ofNullable(updated.get());
    }

    @Override
    public Optional<DeviceRuntimeState> markOffline(DeviceId deviceId, String sessionId, Instant occurredAt) {
        AtomicReference<DeviceRuntimeState> updated = new AtomicReference<>();
        states.asMap().computeIfPresent(deviceId, (ignored, current) -> {
            if (current.onlineStatus() != DeviceOnlineStatus.ONLINE || !current.sessionId().equals(sessionId)) {
                return current;
            }
            DeviceRuntimeState next = new DeviceRuntimeState(deviceId, DeviceOnlineStatus.OFFLINE,
                    sessionId, current.connectedAt(), current.lastSeenAt(), occurredAt);
            updated.set(next);
            return next;
        });
        if (updated.get() != null) {
            metrics.connectedDevices(onlineCount());
        }
        return Optional.ofNullable(updated.get());
    }

    @Override
    public Optional<DeviceRuntimeState> find(DeviceId deviceId) {
        return Optional.ofNullable(states.getIfPresent(deviceId));
    }

    @Override
    public long onlineCount() {
        return states.asMap().values().stream()
                .filter(state -> state.onlineStatus() == DeviceOnlineStatus.ONLINE)
                .count();
    }
}
