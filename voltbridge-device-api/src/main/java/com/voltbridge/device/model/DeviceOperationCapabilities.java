package com.voltbridge.device.model;

/** Operations that a protocol adapter can encode and execute for a device. */
public record DeviceOperationCapabilities(boolean startCharging, boolean stopCharging,
                                          boolean timeSync, boolean reset) {

    public static final DeviceOperationCapabilities NONE =
            new DeviceOperationCapabilities(false, false, false, false);
}
