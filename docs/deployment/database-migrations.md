# Database migrations

VoltBridge uses Flyway versioned migrations for PostgreSQL. The current `V1__baseline.sql` creates the complete initial schema, including tariff roles, retirement boundaries, and the durable tariff revision high-water mark. Flyway is the only schema initialization path; the migration does not add a second `schema_version` mechanism.

## Fresh database

Monolith and Business Bootstrap run Flyway at application startup. Flyway is the sole schema initializer; Spring SQL initialization is not configured. A fresh empty database applies V1 and records it in `flyway_schema_history`. Repeated starts validate the history and do not rerun it. `dev-seed.sql` remains an explicit development-only script and is not part of migration execution.

## Recreate the local development database after a V1 baseline change

The V1 baseline is still being designed and is intentionally changed directly before it is frozen. A local database that already applied an earlier V1 has an old Flyway checksum and must not be made to accept the new baseline with `flyway repair` or `baseline`. The current local database contents are disposable development, seed, test, and simulator data. Stop VoltBridge application processes, then recreate only the `voltbridge` database in the Compose PostgreSQL service:

```powershell
docker compose -f .\deploy\docker-compose.yml exec -T postgres dropdb -U postgres --if-exists voltbridge
docker compose -f .\deploy\docker-compose.yml exec -T postgres createdb -U postgres voltbridge
```

These commands preserve the Compose volume and other service data while deleting the selected PostgreSQL database. Start the VoltBridge application only after the empty database has been recreated; Flyway will then apply the current V1. Run `dev-seed.sql` separately only when demo fixtures are wanted. Never run this reset against a shared or production database.

## Future changes

Never edit a migration that has been applied to a shared database. Add schema changes as new forward-only migrations after the current version. Keep fixture and seed data out of versioned schema migrations.

## Verification

`voltbridge-infra-persistence` contains PostgreSQL Testcontainers tests that start from an empty database, apply V1, verify the current device status model, tariff role and revision constraints, period and meter-sample tables, seed idempotency/reset behavior, and call Flyway again to model restart. Tariff lifecycle integration tests also exercise scheduled overlap activation, fallback revision switching and transaction-level concurrency against PostgreSQL. Tests using Testcontainers require Docker; on Windows Docker Desktop setups whose negotiated API is newer than the bundled docker-java default, pass `"-Dapi.version=1.41"` to Maven.
