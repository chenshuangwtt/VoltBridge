# Database migrations

VoltBridge uses Flyway versioned migrations for PostgreSQL. `voltbridge-infra-persistence/src/main/resources/db/migration/V1__baseline.sql` captures the complete schema that existed before Flyway was introduced. It is a baseline of the current schema, not a fabricated sequence of the old `001/002/006/007/008/009` entries from the legacy `schema_version` table.

## Fresh database

Monolith and Business Bootstrap run Flyway at application startup. Flyway is the sole schema initializer; Spring SQL initialization is not configured. A fresh empty database applies V1 and receives a `flyway_schema_history` entry. Repeated starts validate the history and do not rerun V1. `dev-seed.sql` remains an explicit development-only script and is not part of migration execution.

## Existing database created by `schema-init.sql`

Back up the database first. Do not drop or recreate it. The application deliberately has `baseline-on-migrate=false`: it will not silently declare an arbitrary non-empty schema current. For an existing database whose schema has been checked against the V1 baseline, run Flyway's explicit baseline operation at version 1 using the same JDBC URL and credentials as the application, with `db/migration` on Flyway's locations. For example, with the Flyway CLI installed:

```powershell
flyway -url="$env:DB_URL" -user="$env:DB_USERNAME" -password="$env:DB_PASSWORD" -locations="filesystem:voltbridge-infra-persistence/src/main/resources/db/migration" -baselineVersion=1 baseline
```

The baseline operation writes Flyway history metadata only; it does not execute V1 or alter business tables. Verify the existing tables, indexes, constraints and sequence against V1 before baselining. If the schema is incomplete or differs, stop and prepare a reviewed, data-preserving repair migration; do not baseline it as if it matched. After the explicit baseline succeeds, normal application startup sees version 1 and does not repeat the DDL.

## Future changes

Add a new forward-only migration such as `V2__describe_change.sql`. Never edit a migration that has been applied to a shared database. Keep fixture and seed data out of versioned schema migrations.

## Verification

`voltbridge-infra-persistence` contains a PostgreSQL Testcontainers test that starts from an empty database, applies the migration, calls Flyway again to model restart, and checks the history and Billing tables. The regular Maven verification also compiles and tests PostgreSQL repositories. Tests using Testcontainers require Docker; on Windows Docker Desktop setups whose negotiated API is newer than the bundled docker-java default, pass `"-Dapi.version=1.41"` to Maven.
