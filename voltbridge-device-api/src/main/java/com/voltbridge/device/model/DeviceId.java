package com.voltbridge.device.model;

public record DeviceId(String value) {

    public DeviceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("device id must not be blank");
        }
    }
}
