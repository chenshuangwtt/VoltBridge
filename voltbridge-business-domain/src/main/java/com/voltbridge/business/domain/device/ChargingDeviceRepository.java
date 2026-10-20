package com.voltbridge.business.domain.device;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

/**
 * 充电设备聚合的领域仓储端口，领域模块不依赖 MyBatis 或 PostgreSQL。
 */
public interface ChargingDeviceRepository {

    Optional<ChargingDevice> findById(UUID id);

    Optional<ChargingDevice> findByDeviceCode(String deviceCode);

    boolean updateStatus(String deviceCode, String status, Instant updatedAt);
}
