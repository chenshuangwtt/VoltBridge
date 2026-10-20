package com.voltbridge.business.domain.connector;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Connector(UUID id, UUID tenantId, UUID stationId, UUID deviceId, int connectorNo,
                        String connectorCode, String connectorType, BigDecimal ratedPower,
                        String status, Instant createdAt, Instant updatedAt) {
}
