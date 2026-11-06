package com.voltbridge.business.application.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.stereotype.Component;

/** Uses the application's configured Jackson mapper for deterministic, escaped JSON snapshots. */
@Component
public class PricingSnapshotSerializer {
    private final ObjectMapper objectMapper;

    public PricingSnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(PricingSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new VoltBridgeException("PRICING_SNAPSHOT_SERIALIZATION_FAILED",
                    "Unable to serialize immutable pricing snapshot");
        }
    }
}
