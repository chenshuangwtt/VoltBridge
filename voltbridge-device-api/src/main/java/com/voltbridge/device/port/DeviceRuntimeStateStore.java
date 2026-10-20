package com.voltbridge.device.port;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.runtime.DeviceRuntimeState;

import java.time.Instant;
import java.util.Optional;

public interface DeviceRuntimeStateStore {

    DeviceRuntimeState markOnline(DeviceId deviceId, String sessionId, Instant occurredAt);

    Optional<DeviceRuntimeState> refreshLastSeen(DeviceId deviceId, String sessionId, Instant occurredAt);

    Optional<DeviceRuntimeState> markOffline(DeviceId deviceId, String sessionId, Instant occurredAt);

    Optional<DeviceRuntimeState> find(DeviceId deviceId);

    long onlineCount();
}
