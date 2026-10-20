package com.voltbridge.device.command;
import com.voltbridge.device.model.DeviceId;
import java.time.Instant;
import java.util.UUID;
/** 平台向设备下发的协议无关对时命令。 */
public record SynchronizeTimeCommand(UUID commandId, DeviceId deviceId, Instant targetTime, Instant createdAt) implements DeviceCommand {}
