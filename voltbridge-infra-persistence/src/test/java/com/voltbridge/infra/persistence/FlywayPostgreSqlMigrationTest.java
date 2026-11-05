package com.voltbridge.infra.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('billing_record','billing_line_item','tariff_plan')")) {
                    assertTrue(result.next());
                    assertEquals(3, result.getInt(1));
                }
                try (var result = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
