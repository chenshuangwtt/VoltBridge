package com.voltbridge.business.domain.connector;

import java.util.Optional;
import java.util.UUID;

public interface ConnectorRepository {

    Optional<Connector> findByDeviceIdAndConnectorNo(UUID deviceId, int connectorNo);
}
