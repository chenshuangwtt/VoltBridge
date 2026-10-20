package com.voltbridge.device.model;

public record ConnectorId(String value) {

    public ConnectorId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("connector id must not be blank");
        }
    }
}
