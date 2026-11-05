package com.voltbridge.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.testing.simulator.core.CompletionMode;
import com.voltbridge.testing.simulator.ocpp16.Ocpp16Simulator;
import com.voltbridge.testing.simulator.scenario.ChargingScenarioConfig;
import com.voltbridge.testing.simulator.scenario.ChargingScenarioRunner;
import com.voltbridge.testing.simulator.scenario.DeterministicChargingProfile;
import com.voltbridge.testing.simulator.scenario.PlatformScenarioControl;
import com.voltbridge.testing.simulator.yunkuaichong.YunKuaiChongV150Simulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Process-level contract over PostgreSQL, Kafka, Redis, Business and two separately-owned gateways. */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "voltbridge.fullE2e", matches = "true")
final class FullDistributedE2ETest {
    static { System.setProperty("api.version", "1.41"); }
    private static final String YKC = "20231212000010";
    private static final String OCPP = "OCPP-SIM-001";
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("voltbridge").withUsername("postgres").withPassword("voltbridge");
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static final List<ManagedProcess> PROCESSES = new ArrayList<>();
    private static int businessHttp, businessGrpc, gatewayAGrpc, gatewayATcp, gatewayAWs;
    private static int gatewayBGrpc, gatewayBTcp, gatewayBWs;
    private static URI business;

    @BeforeAll static void startTopology() throws Exception {
        Path root = Path.of(System.getProperty("project.root")).toAbsolutePath();
        businessHttp = freePort(); businessGrpc = freePort();
        gatewayAGrpc = freePort(); gatewayATcp = freePort(); gatewayAWs = freePort();
        gatewayBGrpc = freePort(); gatewayBTcp = freePort(); gatewayBWs = freePort();
        business = URI.create("http://127.0.0.1:" + businessHttp);
        String redis = REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        PROCESSES.add(start(root, "voltbridge-business-bootstrap", "com.voltbridge.business.bootstrap.VoltBridgeBusinessApplication",
                env("HTTP_PORT", businessHttp, "BUSINESS_GRPC_PORT", businessGrpc, "DB_URL", POSTGRES.getJdbcUrl(),
                        "DB_USERNAME", POSTGRES.getUsername(), "DB_PASSWORD", POSTGRES.getPassword(), "REDIS_HOST", REDIS.getHost(),
                        "REDIS_PORT", REDIS.getMappedPort(6379), "KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers(),
                        "KAFKA_CONSUMER_GROUP", "voltbridge-e2e-business")));
        await("Business HTTP readiness", Duration.ofMinutes(2), () -> healthy(business));
        seed();
        startGateway(root, redis, "gateway-a", gatewayAGrpc, gatewayATcp, gatewayAWs, businessGrpc);
        startGateway(root, redis, "gateway-b", gatewayBGrpc, gatewayBTcp, gatewayBWs, businessGrpc);
        await("Gateway A and B TCP, WebSocket and gRPC readiness", Duration.ofMinutes(2), () -> PROCESSES.size() == 3
                && PROCESSES.stream().allMatch(ManagedProcess::isAlive)
                && listening(gatewayAGrpc) && listening(gatewayATcp) && listening(gatewayAWs)
                && listening(gatewayBGrpc) && listening(gatewayBTcp) && listening(gatewayBWs));
    }

    @AfterAll static void stopTopology() throws Exception {
        for (int index = PROCESSES.size() - 1; index >= 0; index--) PROCESSES.get(index).stop();
        assertThat(PROCESSES).allMatch(process -> !process.isAlive());
    }

    @Test @Order(1) void yunkuaichongLifecycleRoutesAcrossProcessesAndFinalizesBilling() throws Exception {
        HttpControl control = new HttpControl(YKC);
        ChargingScenarioConfig config = config("distributed-ykc", YKC, CompletionMode.REMOTE_STOP);
        try (YunKuaiChongV150Simulator simulator = new YunKuaiChongV150Simulator("127.0.0.1", gatewayBTcp, YKC, 1)) {
            var result = new ChargingScenarioRunner().runRemoteStartScenario(simulator, config, control);
            assertThat(result.finalStatus()).isEqualTo("COMPLETED");
            assertThat(control.status("/api/commands/" + result.startCommandId(), "status")).isEqualTo("SUCCEEDED");
            assertThat(control.status("/api/commands/" + result.stopCommandId(), "status")).isEqualTo("SUCCEEDED");
            control.assertBilling(result.chargingTransactionId(), "6.00");
        }
    }

    @Test @Order(2) void ocppLifecycleUsesProtocolSpecificTransactionFlowAndFinalizesBilling() throws Exception {
        HttpControl control = new HttpControl(OCPP);
        ChargingScenarioConfig config = config("distributed-ocpp", OCPP, CompletionMode.REMOTE_STOP);
        try (Ocpp16Simulator simulator = new Ocpp16Simulator("127.0.0.1", gatewayBWs, "/ocpp", OCPP, 1)) {
            var result = new ChargingScenarioRunner().runRemoteStartScenario(simulator, config, control);
            assertThat(result.finalStatus()).isEqualTo("COMPLETED");
            control.assertBilling(result.chargingTransactionId(), "6.00");
        }
    }

    @Test @Order(3) void staleGatewayADisconnectCannotRemoveGatewayBRouteOrStealNextCommand() throws Exception {
        ChargingScenarioConfig config = config("cross-gateway-fencing", YKC, CompletionMode.REMOTE_STOP);
        HttpControl control = new HttpControl(YKC);
        var samples = new DeterministicChargingProfile(config).samples();
        try (YunKuaiChongV150Simulator simulatorA = new YunKuaiChongV150Simulator("127.0.0.1", gatewayATcp, YKC, 1);
             YunKuaiChongV150Simulator simulatorB = new YunKuaiChongV150Simulator("127.0.0.1", gatewayBTcp, YKC, 1)) {
            simulatorA.connect(); simulatorA.loginOrBoot();
            assertThat(redisField(YKC, "gatewayNodeId")).isEqualTo("gateway-a");
            String staleSession = redisField(YKC, "sessionId");
            String staleEpoch = redisField(YKC, "connectionEpoch");
            String staleInstance = redisField(YKC, "gatewayNodeInstanceId");

            simulatorB.connect(); simulatorB.loginOrBoot();
            assertThat(redisField(YKC, "gatewayNodeId")).isEqualTo("gateway-b");
            assertThat(redisField(YKC, "gatewayNodeInstanceId")).isNotEqualTo(staleInstance);
            assertThat(redisField(YKC, "sessionId")).isNotEqualTo(staleSession);
            assertThat(redisField(YKC, "connectionEpoch")).isNotEqualTo(staleEpoch);
            String currentSession = redisField(YKC, "sessionId");
            await("Business Kafka ONLINE state for Gateway B session", Duration.ofSeconds(10),
                    () -> businessLogContains("deviceCode=" + YKC + ", sessionId=" + currentSession + ", onlineStatus=ONLINE"));

            simulatorA.disconnect();
            await("B route after stale A disconnect", Duration.ofSeconds(5), () -> "gateway-b".equals(redisFieldUnchecked(YKC, "gatewayNodeId")));

            UUID startId = control.requestRemoteStart();
            simulatorB.awaitRemoteStart(Duration.ofSeconds(10));
            simulatorB.acceptRemoteStart();
            control.awaitStartAcknowledged(startId);
            simulatorB.beginCharging(samples.getFirst());
            control.awaitStartSucceeded(startId);
            control.awaitTransactionCharging();
            simulatorB.sendChargingProgress(samples.get(1));
            UUID stopId = control.requestRemoteStop();
            simulatorB.awaitRemoteStop(Duration.ofSeconds(10));
            simulatorB.acceptRemoteStop();
            control.awaitStopAcknowledged(stopId);
            simulatorB.completeCharging(samples.getLast(), CompletionMode.REMOTE_STOP, false);
            control.awaitStopSucceeded(stopId);
            control.awaitTransactionCompleted();
            UUID transactionId = transactionIdForCommand(startId);
            control.assertBilling(transactionId, "6.00");
            assertThat(redisField(YKC, "gatewayNodeId")).isEqualTo("gateway-b");
        }
    }

    private static void startGateway(Path root, String redis, String node, int grpc, int tcp, int ws, int businessGrpcPort) throws Exception {
        PROCESSES.add(start(root, "voltbridge-gateway-bootstrap", "com.voltbridge.gateway.bootstrap.VoltBridgeGatewayApplication",
                env("GATEWAY_NODE_ID", node, "GATEWAY_GRPC_PORT", grpc, "GATEWAY_GRPC_ADVERTISED_PORT", grpc,
                        "TCP_PORT", tcp, "OCPP_WEBSOCKET_PORT", ws, "BUSINESS_GRPC_TARGET", "127.0.0.1:" + businessGrpcPort,
                        "REDIS_HOST", REDIS.getHost(), "REDIS_PORT", REDIS.getMappedPort(6379),
                        "KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers())));
    }

    private static ManagedProcess start(Path root, String module, String mainClass, String... env) throws Exception {
        Path jar = root.resolve(module).resolve("target").resolve(module + "-0.1.0-SNAPSHOT.jar");
        assertThat(jar).as("packaged executable for %s", module).exists();
        Path log = root.resolve("voltbridge-e2e/target/e2e-logs"); Files.createDirectories(log);
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString());
        for (int index = 0; index < env.length; index += 2) builder.environment().put(env[index], env[index + 1]);
        Path logFile = log.resolve(module + "-" + UUID.randomUUID() + ".log");
        builder.redirectErrorStream(true).redirectOutput(logFile.toFile());
        return new ManagedProcess(builder.start(), logFile);
    }

    private static String[] env(Object... pairs) {
        String[] values = new String[pairs.length];
        for (int i = 0; i < pairs.length; i += 2) { values[i] = pairs[i].toString(); values[i + 1] = pairs[i + 1].toString(); }
        return values;
    }

    private static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("insert into tenant(id,tenant_code,tenant_name,status) values ('" + TENANT + "','E2E','E2E','ACTIVE')");
            s.execute("insert into charging_station(id,tenant_id,station_code,station_name,status) values ('22222222-2222-4222-8222-222222222222','" + TENANT + "','E2E','E2E','ACTIVE')");
            seedDevice(s, "33333333-3333-4333-8333-333333333331", YKC, "YUN_KUAI_CHONG_150", "YKC 1.5");
            seedDevice(s, "33333333-3333-4333-8333-333333333332", OCPP, "OCPP_16", "1.6J");
            s.execute("insert into tariff_plan(id,tenant_id,name,currency,pricing_type,energy_price_per_kwh,service_fee_per_kwh,effective_from,status,activated_at) values ('44444444-4444-4444-8444-444444444444','" + TENANT + "','E2E fixed tariff','CNY','FIXED',1.000000,0.200000,'2020-01-01T00:00:00Z','ACTIVE',now())");
        }
    }

    private static void seedDevice(Statement s, String id, String code, String protocol, String version) throws Exception {
        s.execute("insert into charging_device(id,tenant_id,station_id,device_code,device_name,protocol,protocol_version,status) values ('" + id + "','" + TENANT + "','22222222-2222-4222-8222-222222222222','" + code + "','E2E','" + protocol + "','" + version + "','REGISTERED')");
        s.execute("insert into charging_connector(id,tenant_id,station_id,device_id,connector_no,connector_code,connector_type,rated_power,status) values ('" + UUID.randomUUID() + "','" + TENANT + "','22222222-2222-4222-8222-222222222222','" + id + "',1,'" + code + "-1','DC',120,'AVAILABLE')");
    }

    private static ChargingScenarioConfig config(String name, String device, CompletionMode mode) {
        return new ChargingScenarioConfig(name, device, 1, Duration.ofMillis(250), Duration.ofMinutes(2), Duration.ofMillis(50),
                10, 75, new BigDecimal("0.0000"), new BigDecimal("5.0000"), new BigDecimal("2000.0000"),
                new BigDecimal("7.2"), mode, Instant.parse("2026-10-19T09:00:00Z"), false);
    }

    private static boolean healthy(URI base) {
        try { return HTTP.send(HttpRequest.newBuilder(base.resolve("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200; }
        catch (Exception ignored) { return false; }
    }

    private static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }

    private static boolean listening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException ignored) { return false; }
    }

    private static void await(String description, Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(100); }
        throw new AssertionError("Timed out waiting for " + description + "; process logs: " + PROCESSES.stream().map(p -> p.log.toString()).toList());
    }

    private static String redisField(String device, String field) throws Exception {
        var result = REDIS.execInContainer("redis-cli", "--raw", "HGET", "voltbridge:v1:route:device:{" + device + "}", field);
        assertThat(result.getExitCode()).isZero(); return result.getStdout().trim();
    }

    private static String redisFieldUnchecked(String device, String field) {
        try { return redisField(device, field); } catch (Exception e) { return ""; }
    }

    private static boolean businessLogContains(String text) {
        try { return Files.readString(PROCESSES.getFirst().log).contains(text); } catch (IOException e) { return false; }
    }

    private static UUID transactionIdForCommand(UUID commandId) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = c.prepareStatement("select id from charging_transaction where command_id = ?")) {
            statement.setObject(1, commandId);
            try (var rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalStateException("No transaction for command " + commandId); return rows.getObject(1, UUID.class); }
        }
    }

    private static JsonNode call(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(business.resolve(path));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException(method + " " + path + " returned " + response.statusCode() + ": " + response.body());
        return JSON.readTree(response.body());
    }

    private static final class HttpControl implements PlatformScenarioControl {
        private final String device;
        private UUID start;
        HttpControl(String device) { this.device = device; }
        @Override public UUID requestRemoteStart() {
            try {
                JsonNode node = call("POST", "/api/devices/" + device + "/commands/start-charging", "{\"connectorNo\":\"1\",\"limitAmount\":50.00,\"logicalCardNo\":\"1234567890123456\",\"physicalCardNo\":\"1234567890123456\",\"businessRef\":\"E2E-" + device + "\"}");
                if (!"gateway-b".equals(redisField(device, "gatewayNodeId"))) {
                    throw new IllegalStateException("Remote start was not routed through Gateway B for " + device);
                }
                start = UUID.fromString(node.path("data").path("commandId").asText()); return start;
            } catch (Exception e) { throw new IllegalStateException("Remote start API failed", e); }
        }
        @Override public UUID requestRemoteStop() { try { return UUID.fromString(call("POST", "/api/devices/" + device + "/commands/stop-charging", "{\"connectorNo\":\"1\"}").path("data").path("commandId").asText()); } catch (Exception e) { throw new IllegalStateException("Remote stop API failed", e); } }
        @Override public void awaitStartAcknowledged(UUID id) throws Exception { waitStatus("/api/commands/" + id, "ACKED"); }
        @Override public void awaitStartSucceeded(UUID id) throws Exception { waitStatus("/api/commands/" + id, "SUCCEEDED"); }
        @Override public void awaitStopAcknowledged(UUID id) throws Exception { waitStatus("/api/commands/" + id, "ACKED"); }
        @Override public void awaitStopSucceeded(UUID id) throws Exception { waitStatus("/api/commands/" + id, "SUCCEEDED"); }
        @Override public void awaitTransactionCharging() throws Exception { waitTransaction("CHARGING"); }
        @Override public void awaitTransactionCompleted() throws Exception { waitTransaction("COMPLETED"); }
        @Override public UUID chargingTransactionId() { try { return UUID.fromString(transaction("COMPLETED").path("id").asText()); } catch (Exception e) { throw new IllegalStateException("Transaction lookup failed", e); } }
        String status(String path, String field) throws Exception { return call("GET", path, null).path("data").path(field).asText(); }
        void assertBilling(UUID transactionId, String amount) throws Exception {
            JsonNode bill = call("GET", "/api/billing-records/by-transaction/" + transactionId, null).path("data");
            assertThat(bill.path("status").asText()).isEqualTo("FINALIZED");
            assertThat(bill.path("totalAmount").decimalValue()).isEqualByComparingTo(amount);
        }
        private void waitStatus(String path, String expected) throws Exception { await("command status " + expected, Duration.ofSeconds(30), () -> { try { return status(path,"status").equals(expected); } catch (Exception e) { return false; } }); }
        private void waitTransaction(String expected) throws Exception { await("transaction status " + expected, Duration.ofSeconds(30), () -> { try { return transaction(expected).path("status").asText().equals(expected); } catch (Exception e) { return false; } }); }
        private JsonNode transaction(String status) throws Exception { JsonNode array = call("GET", "/api/transactions?deviceCode=" + device + "&connectorNo=1&status=" + status, null).path("data"); return array.isArray() && !array.isEmpty() ? array.get(0) : JSON.createObjectNode(); }
        private JsonNode call(String method, String path, String body) throws Exception {
            return FullDistributedE2ETest.call(method, path, body);
        }
    }

    private static final class ManagedProcess {
        private final Process process;
        private final Path log;
        ManagedProcess(Process process, Path log) { this.process = process; this.log = log; }
        boolean isAlive() { return process.isAlive(); }
        void stop() throws Exception { if (process.isAlive()) { process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } } }
    }
}
