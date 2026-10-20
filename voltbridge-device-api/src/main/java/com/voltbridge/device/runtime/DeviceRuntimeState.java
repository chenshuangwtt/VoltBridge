package com.voltbridge.device.runtime;

import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;

import java.time.Instant;

public record DeviceRuntimeState(DeviceId deviceId, DeviceOnlineStatus onlineStatus,
                                 String sessionId, Instant connectedAt, Instant lastSeenAt,
                                 Instant offlineAt) {
}
