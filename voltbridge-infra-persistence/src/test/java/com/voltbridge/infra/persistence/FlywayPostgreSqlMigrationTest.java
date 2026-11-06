package com.voltbridge.infra.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayPostgreSqlMigrationTest {
    @Test
    void migratesEmptyDatabaseAndRestartDoesNotRepeatDdl() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .baselineVersion("1")
                    .load();

            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (var connection = java.sql.DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('billing_record','billing_line_item','tariff_plan','tariff_period','charging_meter_sample','tariff_revision_counter')")) {
                    assertTrue(result.next());
                    assertEquals(6, result.getInt(1));
                }
                try (var result = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='tariff_plan'
                          AND column_name IN ('family_id','revision','timezone','role','retired_at')
                        """)) {
                    assertTrue(result.next());
                    assertEquals(5, result.getInt(1));
                }
                try (var result = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='tariff_plan'
                          AND column_name IN ('energy_price_per_kwh','service_fee_per_kwh')
                        """)) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
                try (var result = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (var result = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='charging_device' AND column_name='business_status'
                        """)) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (var result = statement.executeQuery("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='charging_device' AND column_name='status'
                        """)) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
                statement.executeUpdate("INSERT INTO tenant (id, tenant_code, tenant_name, status) VALUES "
                        + "('99999999-9999-4999-8999-999999999997','TARIFF-TEST','Tariff test','ACTIVE')");
                statement.executeUpdate("""
                        INSERT INTO tariff_plan (id, family_id, revision, tenant_id, name, currency,
                            pricing_type, timezone, effective_from, status)
                        VALUES ('99999999-9999-4999-8999-999999999996',
                            '99999999-9999-4999-8999-999999999995', 1,
                            '99999999-9999-4999-8999-999999999997', 'Tariff V1', 'CNY',
                            'FIXED', 'Asia/Shanghai', CURRENT_TIMESTAMP, 'DRAFT')
                        """);
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO tariff_plan (id, family_id, revision, tenant_id, name, currency,
                            pricing_type, timezone, effective_from, status)
                        VALUES ('99999999-9999-4999-8999-999999999994',
                            '99999999-9999-4999-8999-999999999995', 1,
                            '99999999-9999-4999-8999-999999999997', 'Duplicate V1', 'CNY',
                            'FIXED', 'Asia/Shanghai', CURRENT_TIMESTAMP, 'DRAFT')
                        """));
            }
        }
    }

    @Test
    void demoSeedIsUtf8IdempotentAndResettableWithoutDeletingOtherTenant() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO tenant (id, tenant_code, tenant_name, status)
                        VALUES ('99999999-9999-4999-8999-999999999999', 'KEEP-TEST', 'Unrelated tenant', 'ACTIVE')
                        """);
                statement.executeUpdate("""
                        INSERT INTO app_user (id, tenant_id, username, password_hash, display_name, status)
                        VALUES ('99999999-9999-4999-8999-999999999998',
                                '99999999-9999-4999-8999-999999999999',
                                'keep-user', 'test-only-hash', 'Keep user', 'ACTIVE')
                        """);
            }

            executeSqlFile(postgres, "db/dev-seed.sql", "/tmp/dev-seed.sql");
            assertDemoSeed(postgres);
            executeSqlFile(postgres, "db/dev-seed.sql", "/tmp/dev-seed.sql");
            assertDemoSeed(postgres);

            executeSqlFile(postgres, "db/reset-dev-seed.sql", "/tmp/reset-dev-seed.sql");
            assertDemoDataAbsent(postgres);
            assertCount(postgres, "SELECT count(*) FROM tenant WHERE tenant_code='KEEP-TEST'", 1);
            assertCount(postgres, "SELECT count(*) FROM app_user WHERE username='keep-user'", 1);

            executeSqlFile(postgres, "db/dev-seed.sql", "/tmp/dev-seed.sql");
            assertDemoSeed(postgres);
            createDemoFallbackRevision(postgres);
            executeSqlFile(postgres, "db/dev-seed.sql", "/tmp/dev-seed.sql");
            assertDemoSeed(postgres);
            assertCount(postgres, "SELECT count(*) FROM tariff_period WHERE tariff_plan_id='50000000-0000-4000-8000-000000000005' "
                    + "AND energy_price_per_kwh=1.25 AND service_fee_per_kwh=0.25", 1);

            try (var connection = java.sql.DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("""
                        SELECT business_status, count(*) FROM charging_device
                        WHERE device_code LIKE 'VB-DEMO-%' GROUP BY business_status ORDER BY business_status
                        """)) {
                    assertTrue(result.next());
                    assertEquals("DISABLED", result.getString(1));
                    assertEquals(2, result.getInt(2));
                    assertTrue(result.next());
                    assertEquals("ENABLED", result.getString(1));
                    assertEquals(6, result.getInt(2));
                    assertTrue(!result.next());
                }

                var tenantId = "11111111-1111-4111-8111-111111111111";
                var stationId = "22222222-2222-4222-8222-222222222222";
                var deviceId = "90000000-0000-4000-8000-000000000001";
                statement.executeUpdate("INSERT INTO charging_device (id, tenant_id, station_id, device_code, device_name, protocol, protocol_version, business_status) "
                        + "VALUES ('" + deviceId + "', '" + tenantId + "', '" + stationId + "', 'STATUS-CHECK', 'Status check', 'OCPP_16', '1.6J', 'ENABLED')");
                statement.executeUpdate("UPDATE charging_device SET business_status='DISABLED' WHERE id='" + deviceId + "'");
                for (String invalidStatus : new String[] {"ONLINE", "OFFLINE", "REGISTERED"}) {
                    assertThrows(SQLException.class, () -> statement.executeUpdate(
                            "UPDATE charging_device SET business_status='" + invalidStatus + "' WHERE id='" + deviceId + "'"));
                }
            }
        }
    }

    private static void executeSqlFile(PostgreSQLContainer<?> postgres, String classpathResource,
                                       String containerPath) throws Exception {
        postgres.copyFileToContainer(MountableFile.forClasspathResource(classpathResource), containerPath);
        var result = postgres.execInContainer("psql", "-X", "-v", "ON_ERROR_STOP=1",
                "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-f", containerPath);
        assertEquals(0, result.getExitCode(),
                "psql failed for " + classpathResource + ":\n" + result.getStdout() + result.getStderr());
    }

    private static void assertDemoSeed(PostgreSQLContainer<?> postgres) throws Exception {
        assertCount(postgres, "SELECT count(*) FROM tenant WHERE id='11111111-1111-4111-8111-111111111111' AND tenant_code='VB-DEMO'", 1);
        assertCount(postgres, "SELECT count(*) FROM charging_station WHERE id='22222222-2222-4222-8222-222222222222' AND station_code='VB-DEMO-STATION-001'", 1);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code LIKE 'VB-DEMO-%'", 8);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code LIKE 'VB-DEMO-%' AND business_status='ENABLED'", 6);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code LIKE 'VB-DEMO-%' AND business_status='DISABLED'", 2);
        assertCount(postgres, "SELECT count(*) FROM charging_connector WHERE connector_code LIKE 'VB-DEMO-%'", 8);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code IN ('OCPP-SIM-001','OCPP-SIM-002','OCPP-SIM-003') AND protocol='OCPP_16' AND protocol_version='1.6J' AND business_status='ENABLED'", 3);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code IN ('20231212000010','20231212000011','20231212000012') AND protocol='YUN_KUAI_CHONG_150' AND protocol_version='1.5' AND business_status='ENABLED' AND device_code ~ '^[0-9]{14}$'", 3);
        assertCount(postgres, "SELECT count(*) FROM charging_connector c JOIN charging_device d ON d.id=c.device_id WHERE d.device_code IN ('OCPP-SIM-001','OCPP-SIM-002','OCPP-SIM-003','20231212000010','20231212000011','20231212000012') AND c.connector_no=1", 6);
        assertCount(postgres, "SELECT count(*) FROM charging_transaction tx JOIN charging_device d ON d.id=tx.device_id WHERE d.device_code IN ('OCPP-SIM-001','OCPP-SIM-002','OCPP-SIM-003','20231212000010','20231212000011','20231212000012') AND tx.status IN ('STARTING','CHARGING','ENDING')", 0);
        assertCount(postgres, "SELECT count(*) FROM charging_transaction tx JOIN charging_device d ON d.id=tx.device_id WHERE d.device_code IN ('OCPP-SIM-001','OCPP-SIM-002','OCPP-SIM-003','20231212000010','20231212000011','20231212000012')", 0);
        assertCount(postgres, "SELECT count(*) FROM device_command dc JOIN charging_device d ON d.id=dc.device_id WHERE d.device_code IN ('OCPP-SIM-001','OCPP-SIM-002','OCPP-SIM-003','20231212000010','20231212000011','20231212000012')", 0);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE tenant_id='11111111-1111-4111-8111-111111111111'", 14);
        assertCount(postgres, "SELECT count(*) FROM charging_connector WHERE tenant_id='11111111-1111-4111-8111-111111111111'", 14);
        assertCount(postgres, "SELECT count(*) FROM device_command WHERE business_ref LIKE 'DEMO-CMD-%'", 12);
        assertCount(postgres, "SELECT count(*) FROM charging_transaction WHERE business_ref LIKE 'DEMO-ORDER-%'", 20);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE name LIKE 'VoltBridge Demo - %'", 3);
        assertCount(postgres, "SELECT count(DISTINCT family_id) FROM tariff_plan WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND role='FALLBACK'", 1);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND role='FALLBACK' AND effective_to IS NULL", 1);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE tenant_id='11111111-1111-4111-8111-111111111111' AND role='FALLBACK' AND status='ACTIVE' AND effective_to IS NULL", 1);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE id='50000000-0000-4000-8000-000000000004' AND role='FALLBACK' AND pricing_type='FIXED'", 1);
        assertCount(postgres, "SELECT count(*) FROM tariff_period WHERE tariff_plan_id='50000000-0000-4000-8000-000000000004' AND period_type='FLAT' AND start_minute=0 AND end_minute=1440 AND energy_price_per_kwh=1.00 AND service_fee_per_kwh=0.20", 1);
        assertCount(postgres, "SELECT count(*) FROM tariff_revision_counter WHERE family_id IN "
                + "(SELECT family_id FROM tariff_plan WHERE name LIKE 'VoltBridge Demo - %' OR "
                + "(role='FALLBACK' AND tenant_id='11111111-1111-4111-8111-111111111111'))", 4);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan a JOIN tariff_plan b "
                + "ON a.tenant_id=b.tenant_id AND a.id < b.id "
                + "WHERE a.role='SCHEDULED' AND b.role='SCHEDULED' AND a.status='ACTIVE' AND b.status='ACTIVE' "
                + "AND (a.effective_to IS NULL OR b.effective_from < a.effective_to) "
                + "AND (b.effective_to IS NULL OR a.effective_from < b.effective_to)", 0);
        assertCount(postgres, "SELECT count(*) FROM tariff_period WHERE tariff_plan_id IN "
                + "(SELECT id FROM tariff_plan WHERE name LIKE 'VoltBridge Demo - %')", 7);
        assertCount(postgres, "SELECT count(*) FROM charging_meter_sample", 0);
        assertCount(postgres, "SELECT count(*) FROM billing_record WHERE id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid", 14);
        assertCount(postgres, "SELECT count(*) FROM billing_line_item li JOIN billing_record br ON br.id=li.billing_record_id WHERE br.id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid", 28);

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT tenant_name FROM tenant
                     WHERE id='11111111-1111-4111-8111-111111111111'
                     """)) {
            assertTrue(result.next());
            assertEquals("VoltBridge 演示租户", result.getString(1));
        }

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT d.protocol, d.business_status, c.status,
                            (SELECT count(*) FROM charging_transaction tx
                             WHERE tx.device_id=d.id AND tx.status IN ('STARTING','CHARGING','ENDING'))
                     FROM charging_device d
                     JOIN charging_connector c ON c.device_id=d.id AND c.connector_no=1
                     WHERE d.device_code='VB-DEMO-006'
                     """)) {
            assertTrue(result.next());
            assertEquals("OCPP_16", result.getString(1));
            assertEquals("ENABLED", result.getString(2));
            assertEquals("AVAILABLE", result.getString(3));
            assertEquals(0, result.getInt(4));
        }

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT tx.energy_kwh, br.total_amount, br.status, tp.pricing_type,
                            period.energy_price_per_kwh, period.service_fee_per_kwh,
                            (SELECT count(*) FROM billing_line_item li WHERE li.billing_record_id=br.id)
                     FROM charging_transaction tx
                     JOIN billing_record br ON br.transaction_id=tx.id
                     JOIN tariff_plan tp ON tp.id=br.tariff_plan_id
                     JOIN tariff_period period ON period.tariff_plan_id=tp.id AND period.sequence_no=1
                     WHERE tx.id='70000000-0000-4000-8000-000000000003'
                     """)) {
            assertTrue(result.next());
            assertEquals(0, result.getBigDecimal(1).compareTo(new java.math.BigDecimal("23.700000")));
            assertEquals(0, result.getBigDecimal(2).compareTo(new java.math.BigDecimal("28.44")));
            assertEquals("FINALIZED", result.getString(3));
            assertEquals("FIXED", result.getString(4));
            assertEquals(0, result.getBigDecimal(5).compareTo(new java.math.BigDecimal("1.000000")));
            assertEquals(0, result.getBigDecimal(6).compareTo(new java.math.BigDecimal("0.200000")));
            assertEquals(2, result.getInt(7));
        }
        assertCount(postgres, "SELECT count(*) FROM tariff_period WHERE tariff_plan_id='50000000-0000-4000-8000-000000000001'", 1);
        assertCount(postgres, "SELECT count(*) FROM tariff_period WHERE tariff_plan_id='50000000-0000-4000-8000-000000000003'", 5);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE id='50000000-0000-4000-8000-000000000003' AND pricing_type='TIME_OF_USE' AND timezone='Asia/Shanghai' AND status='DRAFT'", 1);

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            String txId = "70000000-0000-4000-8000-000000000003";
            statement.executeUpdate("INSERT INTO charging_meter_sample "
                    + "(event_id, transaction_id, occurred_at, energy_kwh, protocol) VALUES "
                    + "('88000000-0000-4000-8000-000000000002', '" + txId + "', "
                    + "CURRENT_TIMESTAMP - INTERVAL '4 seconds', 10.050000, 'OCPP_16'), "
                    + "('88000000-0000-4000-8000-000000000001', '" + txId + "', "
                    + "CURRENT_TIMESTAMP - INTERVAL '2 seconds', 10.100000, 'OCPP_16')");
            statement.executeUpdate("INSERT INTO charging_meter_sample "
                    + "(event_id, transaction_id, occurred_at, energy_kwh, protocol) VALUES "
                    + "('88000000-0000-4000-8000-000000000001', '" + txId + "', "
                    + "CURRENT_TIMESTAMP, 999.000000, 'OCPP_16') ON CONFLICT (event_id) DO NOTHING");
            assertCount(postgres, "SELECT count(*) FROM charging_meter_sample WHERE transaction_id='" + txId + "'", 2);
            try (var result = statement.executeQuery("SELECT event_id::text FROM charging_meter_sample "
                    + "WHERE transaction_id='" + txId + "' ORDER BY occurred_at, event_id")) {
                assertTrue(result.next());
                assertEquals("88000000-0000-4000-8000-000000000002", result.getString(1));
                assertTrue(result.next());
                assertEquals("88000000-0000-4000-8000-000000000001", result.getString(1));
            }
            statement.executeUpdate("DELETE FROM charging_meter_sample WHERE event_id IN "
                    + "('88000000-0000-4000-8000-000000000001','88000000-0000-4000-8000-000000000002')");
        }
        assertCount(postgres, "SELECT count(*) FROM charging_meter_sample", 0);
    }

    private static void assertDemoDataAbsent(PostgreSQLContainer<?> postgres) throws Exception {
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code LIKE 'VB-DEMO-%'", 0);
        assertCount(postgres, "SELECT count(*) FROM charging_device WHERE device_code IN ('OCPP-SIM-001','OCPP-SIM-002','OCPP-SIM-003','20231212000010','20231212000011','20231212000012')", 0);
        assertCount(postgres, "SELECT count(*) FROM device_command WHERE business_ref LIKE 'DEMO-CMD-%'", 0);
        assertCount(postgres, "SELECT count(*) FROM charging_transaction WHERE business_ref LIKE 'DEMO-ORDER-%'", 0);
        assertCount(postgres, "SELECT count(*) FROM charging_meter_sample", 0);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE name LIKE 'VoltBridge Demo - %'", 0);
        assertCount(postgres, "SELECT count(*) FROM tariff_plan WHERE id='50000000-0000-4000-8000-000000000004' AND role='FALLBACK'", 0);
        assertCount(postgres, "SELECT count(*) FROM billing_record WHERE id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid", 0);
        assertCount(postgres, "SELECT count(*) FROM billing_line_item li JOIN billing_record br ON br.id=li.billing_record_id WHERE br.id BETWEEN '80000000-0000-4000-8000-000000000001'::uuid AND '80000000-0000-4000-8000-000000000014'::uuid", 0);
    }

    private static void createDemoFallbackRevision(PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE tariff_plan SET effective_to=TIMESTAMPTZ '2035-01-01T00:00:00Z', "
                    + "retired_at=TIMESTAMPTZ '2035-01-01T00:00:00Z', status='RETIRED' "
                    + "WHERE id='50000000-0000-4000-8000-000000000004'");
            statement.executeUpdate("INSERT INTO tariff_plan (id,family_id,revision,tenant_id,role,name,currency,pricing_type,timezone,effective_from,status,activated_at) "
                    + "VALUES ('50000000-0000-4000-8000-000000000005','51000000-0000-4000-8000-000000000004',2,"
                    + "'11111111-1111-4111-8111-111111111111','FALLBACK','默认固定资费','CNY','FIXED','Asia/Shanghai',"
                    + "TIMESTAMPTZ '2035-01-01T00:00:00Z','ACTIVE',CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO tariff_period (tariff_plan_id,sequence_no,period_type,start_minute,end_minute,energy_price_per_kwh,service_fee_per_kwh) "
                    + "VALUES ('50000000-0000-4000-8000-000000000005',1,'FLAT',0,1440,1.25,0.25)");
            statement.executeUpdate("UPDATE tariff_revision_counter SET last_revision=2 "
                    + "WHERE family_id='51000000-0000-4000-8000-000000000004'");
        }
    }

    private static void assertCount(PostgreSQLContainer<?> postgres, String sql, int expected) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1), sql);
        }
    }
}
