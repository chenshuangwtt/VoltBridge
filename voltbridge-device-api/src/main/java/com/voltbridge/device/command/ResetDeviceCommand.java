package com.voltbridge.device.command;
import com.voltbridge.device.model.DeviceId;
import java.time.Instant;
import java.util.UUID;
/** 平台请求设备按其协议定义的默认方式重启；不臆造软硬重启语义。 */
public record ResetDeviceCommand(UUID commandId, DeviceId deviceId, boolean whenIdle, Instant createdAt) implements DeviceCommand {}
