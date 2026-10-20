package com.voltbridge.bootstrap;

import com.voltbridge.business.domain.connector.ConnectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "VOLTBRIDGE_RUN_DB_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "voltbridge.transport.tcp.port=0"
})
class OfflineDevicePostgreSqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ConnectorRepository connectorRepository;

    @Test
    void persistsDeviceOfflineFailureForPostgreSqlDeviceWithoutSession() {
        UUID tenantId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        String suffix = deviceId.toString().substring(0, 8);
        String deviceCode = String.format("98%012d", Integer.toUnsignedLong(deviceId.hashCode()));
        insertFixture(tenantId, stationId, deviceId, connectorId, suffix, deviceCode);
        try {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM charging_connector
                    WHERE device_id = ? AND connector_no = 1
                    """, Integer.class, deviceId)).isEqualTo(1);
            assertThat(connectorRepository.findByDeviceIdAndConnectorNo(deviceId, 1)).isPresent();
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/devices/" + deviceCode + "/commands/start-charging",
                    Map.of("connectorNo", "01", "limitAmount", new BigDecimal("50.00"),
                            "businessRef", "OFFLINE-" + suffix),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("\"code\":\"DEVICE_OFFLINE\"");
            CommandRow command = jdbcTemplate.queryForObject("""
                    SELECT command_status, failure_code
                    FROM device_command
                    WHERE device_id = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """, (resultSet, rowNum) -> new CommandRow(
                    resultSet.getString("command_status"), resultSet.getString("failure_code")), deviceId);
            assertThat(command).isEqualTo(new CommandRow("FAILED", "DEVICE_OFFLINE"));
        } finally {
            jdbcTemplate.update("""
                    DELETE FROM billing_line_item WHERE billing_record_id IN (
                        SELECT id FROM billing_record WHERE transaction_id IN (
                            SELECT id FROM charging_transaction WHERE device_id = ?))
                    """, deviceId);
            jdbcTemplate.update("""
                    DELETE FROM billing_record WHERE transaction_id IN (
                        SELECT id FROM charging_transaction WHERE device_id = ?)
                    """, deviceId);
            jdbcTemplate.update("DELETE FROM charging_transaction WHERE device_id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM device_command WHERE device_id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM charging_connector WHERE device_id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM charging_device WHERE id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM charging_station WHERE id = ?", stationId);
            jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", tenantId);
        }
    }

    private void insertFixture(UUID tenantId, UUID stationId, UUID deviceId, UUID connectorId,
                               String suffix, String deviceCode) {
        jdbcTemplate.update("""
                INSERT INTO tenant(id, tenant_code, tenant_name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, tenantId, "phase4-" + suffix, "Phase 4 Test Tenant");
        jdbcTemplate.update("""
                INSERT INTO charging_station(id, tenant_id, station_code, station_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, stationId, tenantId, "station-" + suffix, "Phase 4 Test Station");
        jdbcTemplate.update("""
                INSERT INTO charging_device(
                    id, tenant_id, station_id, device_code, device_name,
                    protocol, protocol_version, status)
                VALUES (?, ?, ?, ?, ?, 'YUN_KUAI_CHONG_150', '1.5', 'REGISTERED')
                """, deviceId, tenantId, stationId, deviceCode, "Phase 4 Offline Device");
        jdbcTemplate.update("""
                INSERT INTO charging_connector(
                    id, tenant_id, station_id, device_id, connector_no,
                    connector_code, connector_type, rated_power, status)
                VALUES (?, ?, ?, ?, 1, ?, 'DC', 120.000, 'AVAILABLE')
                """, connectorId, tenantId, stationId, deviceId, "connector-" + suffix);
    }

    private record CommandRow(String status, String failureCode) {
    }
}
