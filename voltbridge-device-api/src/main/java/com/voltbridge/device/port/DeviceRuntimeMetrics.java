package com.voltbridge.device.port;

public interface DeviceRuntimeMetrics {

    DeviceRuntimeMetrics NOOP = new DeviceRuntimeMetrics() {
    };

    default void connectedDevices(long count) {
    }

    default void heartbeatReceived() {
    }

    default void offlineTimeout() {
    }

    default void connectorStatusChanged() {
    }

    default void chargingProgressReceived() {
    }
}
