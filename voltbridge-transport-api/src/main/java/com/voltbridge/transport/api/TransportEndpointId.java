package com.voltbridge.transport.api;

public record TransportEndpointId(String value) {

    public TransportEndpointId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("transport endpoint id must not be blank");
        }
    }
}
