package com.voltbridge.common.id;

import java.util.UUID;

public record DomainId(UUID value) {

    public DomainId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static DomainId newId() {
        return new DomainId(UUID.randomUUID());
    }
}
