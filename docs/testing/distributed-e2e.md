# Distributed process-level E2E

Run the actual distributed topology from the repository root with JDK 21 and Docker (the Wrapper downloads Maven 3.9.9):

```powershell
./mvnw -Pfull-e2e verify
```

The `voltbridge-e2e` reactor module depends on the executable Business and Gateway bootstrap modules. Maven packages those jars before the E2E test starts. Testcontainers starts an empty PostgreSQL 16 database, Kafka, and Redis; Business applies the committed Flyway migrations. The test inserts exactly two device/connector pairs and one active fixed tariff into the disposable database.

The harness allocates host ports at runtime for Business HTTP and gRPC, both Gateway gRPC listeners, both YunKuaiChong TCP listeners, and both OCPP WebSocket listeners. It starts Business and Gateway A and B as separate Java processes, redirects each process's combined stdout/stderr to `voltbridge-e2e/target/e2e-logs`, polls HTTP and transport readiness, and terminates all child processes in `@AfterAll` (forcibly after a bounded graceful shutdown period).

The protocol scenarios use the shared `voltbridge-testing` simulators and `ChargingScenarioRunner`; they call the Business REST API and poll its public command/transaction/billing views. Gateway command delivery therefore traverses Redis route lookup and gRPC. Device events return through the real Kafka publisher/consumer into the PostgreSQL-backed transaction lifecycle. Both scenarios assert `COMPLETED`, `FINALIZED`, and the deterministic CNY 6.00 fixed-rate bill for 5 kWh at CNY 1.00 energy plus CNY 0.20 service fee.

Test logs remain under `voltbridge-e2e/target/e2e-logs` after a failure for diagnosis. Surefire passes Docker API 1.41 to Testcontainers test JVMs. Keep this compatibility setting: Windows Docker Desktop versions whose minimum API is 1.40 reject docker-java clients that negotiate below that minimum, while API 1.41 is supported by both the local Docker Desktop setup and GitHub-hosted Ubuntu Docker. This is a test-client property, not an application runtime API setting.

After Business becomes healthy, the process-level suite also verifies that its OpenAPI JSON endpoint returns 200 with key business routes and that `/swagger-ui/index.html` resolves. The Monolith uses the same `voltbridge-business-web` module and OpenAPI configuration.

The suite also opens the same YKC device on A and B concurrently, verifies the Redis route changes across `gatewayNodeId`, `gatewayNodeInstanceId`, `sessionId`, and `connectionEpoch`, closes A only after B owns the route, then proves the next real remote-start command reaches B and completes there. Forced Gateway crash/TTL recovery is not covered. Redis outage remains a non-blocking chaos-profile candidate because it changes dependency availability during command dispatch.
