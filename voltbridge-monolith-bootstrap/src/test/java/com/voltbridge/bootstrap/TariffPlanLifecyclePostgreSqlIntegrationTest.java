package com.voltbridge.bootstrap;

import com.voltbridge.business.application.billing.ActivateTariffPlanUseCase;
import com.voltbridge.business.domain.billing.BillingLineItem;
import com.voltbridge.business.domain.billing.BillingLineItemType;
import com.voltbridge.business.domain.billing.BillingRecord;
import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.BillingStatus;
import com.voltbridge.business.application.billing.CreateTariffPlanUseCase;
import com.voltbridge.business.application.billing.CreateTariffRevisionUseCase;
import com.voltbridge.business.application.billing.CreateFallbackTariffRevisionUseCase;
import com.voltbridge.business.application.billing.DefaultTariffResolver;
import com.voltbridge.business.application.billing.DeleteTariffPlanUseCase;
import com.voltbridge.business.application.billing.EnsureTenantFallbackTariffUseCase;
import com.voltbridge.business.application.billing.RetireTariffPlanUseCase;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.common.exception.VoltBridgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.web-application-type=none",
        "voltbridge.transport.tcp.port=0",
        "voltbridge.transport.websocket.enabled=false"
})
class TariffPlanLifecyclePostgreSqlIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BillingRecordRepository billingRecords;
    @Autowired private TariffPlanRepository tariffPlanRepository;
    @Autowired private CreateTariffPlanUseCase createPlan;
    @Autowired private CreateTariffRevisionUseCase createRevision;
    @Autowired private CreateFallbackTariffRevisionUseCase createFallbackRevision;
    @Autowired private EnsureTenantFallbackTariffUseCase ensureFallback;
    @Autowired private DefaultTariffResolver resolver;
    @Autowired private ActivateTariffPlanUseCase activate;
    @Autowired private RetireTariffPlanUseCase retire;
    @Autowired private DeleteTariffPlanUseCase delete;

    private UUID tenantId;
    private final Set<UUID> familyIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void createTenant() {
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, tenant_code, tenant_name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenantId, "TARIFF-LIFE-" + tenantId.toString().substring(0, 8), "Tariff lifecycle integration tenant");
        familyIds.clear();
    }

    @AfterEach
    void removeTenantFixtures() {
        if (tenantId == null) return;
        jdbc.update("DELETE FROM tariff_period WHERE tariff_plan_id IN (SELECT id FROM tariff_plan WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM tariff_plan WHERE tenant_id = ?", tenantId);
        for (UUID familyId : familyIds) {
            jdbc.update("DELETE FROM tariff_revision_counter WHERE family_id = ?", familyId);
        }
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    void postgresEnforcesHalfOpenRangesAndAllowsDraftOverlapAndAdjacentActivation() {
        TariffPlan first = create("Standard V1", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z");
        activate.execute(first.id());

        TariffPlan overlap = create("Overlapping V2 draft", "2026-05-01T00:00:00Z", "2026-08-01T00:00:00Z");
        TariffPlan secondOverlap = create("Second overlapping draft", "2026-06-01T00:00:00Z", null);
        assertThat(overlap.status()).isEqualTo(TariffPlanStatus.DRAFT);
        assertThat(secondOverlap.status()).isEqualTo(TariffPlanStatus.DRAFT);
        assertConflict(overlap);

        TariffPlan adjacent = create("Adjacent V2", "2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z");
        assertThat(activate.execute(adjacent.id()).status()).isEqualTo(TariffPlanStatus.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_plan WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    void tenantGetsFallbackAndCurrentScheduledCanRetireWithoutLosingHistoricalResolution() {
        TariffPlan fallback = ensureFallback.execute(tenantId);
        familyIds.add(fallback.familyId());
        assertThat(fallback.role()).isEqualTo(TariffRole.FALLBACK);
        assertThat(fallback.pricingType()).isEqualTo(PricingType.FIXED);
        assertThat(fallback.periods()).singleElement().satisfies(period -> {
            assertThat(period.startMinute()).isZero();
            assertThat(period.endMinute()).isEqualTo(1440);
            assertThat(period.energyPricePerKwh()).isEqualByComparingTo("1.00");
            assertThat(period.serviceFeePerKwh()).isEqualByComparingTo("0.20");
        });

        Instant startedBeforeRetire = Instant.now().minusSeconds(30);
        TariffPlan scheduled = create("Current Scheduled", startedBeforeRetire.minusSeconds(3600).toString(), null);
        activate.execute(scheduled.id());
        assertThat(resolver.resolve(transaction(startedBeforeRetire)).id()).isEqualTo(scheduled.id());

        TariffPlan retired = retire.execute(scheduled.id());
        assertThat(retired.status()).isEqualTo(TariffPlanStatus.RETIRED);
        assertThat(retired.effectiveTo()).isEqualTo(retired.retiredAt());
        assertThat(resolver.resolve(transaction(startedBeforeRetire)).id()).isEqualTo(retired.id());
        assertThat(resolver.resolve(transaction(retired.retiredAt().plusMillis(1))).id()).isEqualTo(fallback.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_plan WHERE tenant_id = ? AND role = 'FALLBACK'",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    void concurrentBillingForOneTransactionReturnsTheCommittedWinner() throws Exception {
        UUID stationId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID tariffId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Instant startedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant endedAt = startedAt.plusSeconds(30);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        jdbc.update("INSERT INTO charging_station (id, tenant_id, station_code, station_name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                stationId, tenantId, "BILL-RACE-" + stationId, "Billing race station");
        jdbc.update("INSERT INTO charging_device (id, tenant_id, station_id, device_code, device_name, protocol, protocol_version, business_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'OCPP_16', '1.6J', 'ENABLED')",
                deviceId, tenantId, stationId, "BILL-RACE-" + deviceId, "Billing race device");
        jdbc.update("INSERT INTO charging_connector (id, tenant_id, station_id, device_id, connector_no, connector_code, status) "
                        + "VALUES (?, ?, ?, ?, 1, ?, 'AVAILABLE')",
                connectorId, tenantId, stationId, deviceId, "BILL-RACE-" + connectorId);
        jdbc.update("""
                INSERT INTO tariff_plan (id, family_id, revision, tenant_id, role, name, currency,
                    pricing_type, timezone, effective_from, status, activated_at)
                VALUES (?, ?, 1, ?, 'SCHEDULED', 'Race plan', 'CNY', 'FIXED', 'Asia/Shanghai', ?, 'ACTIVE', ?)
                """, tariffId, familyId, tenantId, Timestamp.from(startedAt.minusSeconds(3600)), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO charging_transaction (id, tenant_id, device_id, device_code, connector_id,
                    connector_no, protocol, status, started_at, ended_at, energy_kwh)
                VALUES (?, ?, ?, ?, ?, 1, 'OCPP_16', 'COMPLETED', ?, ?, 1.000000)
                """, transactionId, tenantId, deviceId, "BILL-RACE-" + deviceId, connectorId,
                Timestamp.from(startedAt), Timestamp.from(endedAt));

        BillingRecord first = billingCandidate(transactionId, tariffId, now);
        BillingRecord second = billingCandidate(transactionId, tariffId, now);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(first, second).stream().map(candidate -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Billing race was not released");
                return billingRecords.saveIfAbsent(candidate);
            })).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Billing contenders did not start");
            start.countDown();
            BillingRecord winner = results.get(0).get(10, TimeUnit.SECONDS);
            BillingRecord other = results.get(1).get(10, TimeUnit.SECONDS);
            assertThat(other.id()).isEqualTo(winner.id());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_record WHERE transaction_id = ?",
                    Integer.class, transactionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_line_item WHERE billing_record_id = ?",
                    Integer.class, winner.id())).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM billing_line_item WHERE billing_record_id IN (SELECT id FROM billing_record WHERE transaction_id = ?)", transactionId);
            jdbc.update("DELETE FROM billing_record WHERE transaction_id = ?", transactionId);
            jdbc.update("DELETE FROM charging_transaction WHERE id = ?", transactionId);
            jdbc.update("DELETE FROM charging_connector WHERE id = ?", connectorId);
            jdbc.update("DELETE FROM charging_device WHERE id = ?", deviceId);
            jdbc.update("DELETE FROM charging_station WHERE id = ?", stationId);
        }
    }

    private BillingRecord billingCandidate(UUID transactionId, UUID tariffId, Instant now) {
        var items = List.of(
                new BillingLineItem(BillingLineItemType.ENERGY_FEE, "Energy", new BigDecimal("1.000000"),
                        "kWh", new BigDecimal("1.00"), new BigDecimal("1.00")),
                new BillingLineItem(BillingLineItemType.SERVICE_FEE, "Service", new BigDecimal("1.000000"),
                        "kWh", new BigDecimal("0.20"), new BigDecimal("0.20")));
        return new BillingRecord(UUID.randomUUID(), tenantId, transactionId, tariffId, "CNY",
                new BigDecimal("1.000000"), new BigDecimal("1.00"), new BigDecimal("0.20"),
                new BigDecimal("1.20"), BigDecimal.ZERO, new BigDecimal("1.20"), BillingStatus.FINALIZED,
                now.minusSeconds(60), now.minusSeconds(30), "{}", items, now, now);
    }

    @Test
    void futureScheduledPlanCanRetire() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant boundary = now.plusSeconds(3600);
        TariffPlan current = create("Current until boundary", now.minusSeconds(3600).toString(), boundary.toString());
        TariffPlan future = create("Future after boundary", boundary.toString(), null);
        activate.execute(current.id());
        activate.execute(future.id());

        assertThat(retire.execute(future.id()).status()).isEqualTo(TariffPlanStatus.RETIRED);
        assertThat(tariffPlanRepository.findById(current.id()).orElseThrow().status())
                .isEqualTo(TariffPlanStatus.ACTIVE);
    }

    @Test
    void fallbackRevisionSwitchPreservesContiguousHistoryAndSerializesConcurrentUpdates() throws Exception {
        TariffPlan v1 = ensureFallback.execute(tenantId);
        familyIds.add(v1.familyId());
        Instant switchAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fallbackRevisionConcurrently(switchAt, "1.10", ready, start));
            var second = executor.submit(() -> fallbackRevisionConcurrently(switchAt, "1.20", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(value -> value.startsWith("CREATED:")).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(value -> value.equals("FALLBACK_SWITCH_TIME_INVALID")).count()).isEqualTo(1);
        }

        List<TariffPlan> revisions = jdbc.query("SELECT id FROM tariff_plan WHERE tenant_id = ? AND role = 'FALLBACK' ORDER BY revision",
                (result, row) -> result.getObject(1, UUID.class), tenantId).stream()
                .map(id -> tariffPlanRepository.findById(id).orElseThrow()).toList();
        assertThat(revisions).hasSize(2);
        TariffPlan old = revisions.getFirst();
        TariffPlan current = revisions.getLast();
        assertThat(current.revision()).isEqualTo(2);
        assertThat(old.status()).isEqualTo(TariffPlanStatus.RETIRED);
        assertThat(old.effectiveTo()).isEqualTo(current.effectiveFrom());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_plan WHERE tenant_id = ? AND role = 'FALLBACK' AND effective_to IS NULL",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(resolver.resolve(transaction(switchAt.minusMillis(1))).id()).isEqualTo(old.id());
        assertThat(resolver.resolve(transaction(switchAt)).id()).isEqualTo(current.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_plan WHERE family_id = ? AND role <> 'FALLBACK'",
                Integer.class, v1.familyId())).isZero();
    }

    @Test
    void deletionKeepsRevisionHighWaterMarkAndRejectsActiveOrRetiredPlans() {
        TariffPlan v1 = create("Family V1", "2026-01-01T00:00:00Z", null);
        TariffPlan v2 = revision(v1);
        TariffPlan v3 = revision(v1);
        delete.execute(v3.id());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tariff_plan WHERE family_id = ?", Integer.class,
                v1.familyId())).isEqualTo(2);
        TariffPlan v4 = revision(v1);
        assertThat(v4.revision()).isEqualTo(4);

        TariffPlan active = create("Active cannot delete", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z");
        activate.execute(active.id());
        assertFailure("TARIFF_ACTIVE_CANNOT_DELETE", () -> delete.execute(active.id()));
        retire.execute(active.id());
        assertFailure("TARIFF_HISTORICAL_CANNOT_DELETE", () -> delete.execute(active.id()));
    }

    @Test
    void twoBusinessTransactionsCannotActivateOverlappingPlansConcurrently() throws Exception {
        TariffPlan first = create("Concurrent A", "2030-01-01T00:00:00Z", "2031-01-01T00:00:00Z");
        TariffPlan second = create("Concurrent B", "2030-06-01T00:00:00Z", "2031-06-01T00:00:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> activateConcurrently(first.id(), ready, start));
            var secondResult = executor.submit(() -> activateConcurrently(second.id(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> results = List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("ACTIVE", "TARIFF_EFFECTIVE_RANGE_CONFLICT");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM tariff_plan
                    WHERE tenant_id = ? AND status = 'ACTIVE'
                      AND effective_from < TIMESTAMPTZ '2031-01-01T00:00:00Z'
                      AND (effective_to IS NULL OR effective_to > TIMESTAMPTZ '2030-01-01T00:00:00Z')
                    """, Integer.class, tenantId)).isEqualTo(1);
        }
    }

    private String activateConcurrently(UUID planId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent activation was not released");
        try {
            activate.execute(planId);
            return "ACTIVE";
        } catch (VoltBridgeException failure) {
            return failure.code();
        }
    }

    private String fallbackRevisionConcurrently(Instant switchAt, String price,
                                                 CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent fallback update was not released");
        try {
            TariffPlan plan = createFallbackRevision.execute(tenantId, switchAt,
                    new BigDecimal(price), new BigDecimal("0.20"));
            return "CREATED:" + plan.revision();
        } catch (VoltBridgeException failure) {
            return failure.code();
        }
    }

    private ChargingTransaction transaction(Instant startedAt) {
        ChargingTransaction tx = ChargingTransaction.start(tenantId, UUID.randomUUID(), "TARIFF-DEVICE",
                UUID.randomUUID(), 1, DeviceProtocol.OCPP_16, UUID.randomUUID(), "TARIFF-ORDER", startedAt.minusMillis(1));
        tx.begin("tariff-protocol-tx", startedAt, BigDecimal.ZERO, ChargingStartMethod.REMOTE, startedAt);
        return tx;
    }

    private TariffPlan create(String name, String from, String to) {
        TariffPlan plan = createPlan.execute(tenantId, name, "CNY", PricingType.FIXED, "Asia/Shanghai",
                List.of(new TariffPeriod(1, TariffPeriodType.FLAT, 0, 1440,
                        BigDecimal.ONE, new BigDecimal("0.20"))), Instant.parse(from),
                to == null ? null : Instant.parse(to));
        familyIds.add(plan.familyId());
        return plan;
    }

    private TariffPlan revision(TariffPlan source) {
        TariffPlan plan = createRevision.execute(source.id(), "Next revision", "CNY", PricingType.FIXED,
                "Asia/Shanghai", List.of(new TariffPeriod(1, TariffPeriodType.FLAT, 0, 1440,
                        BigDecimal.ONE, new BigDecimal("0.20"))), Instant.parse("2026-01-01T00:00:00Z"), null);
        familyIds.add(plan.familyId());
        return plan;
    }

    private void assertConflict(TariffPlan candidate) {
        assertThatThrownBy(() -> activate.execute(candidate.id()))
                .isInstanceOf(VoltBridgeException.class)
                .hasMessageContaining("Standard V1")
                .hasMessageContaining(candidate.id().toString())
                .hasMessageContaining("2026-05-01T00:00:00Z");
    }

    private static void assertFailure(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(VoltBridgeException.class)
                .extracting(error -> ((VoltBridgeException) error).code()).isEqualTo(code);
    }
}
