package com.voltbridge.business.application.billing;

import com.voltbridge.business.domain.billing.BillingRecordRepository;
import com.voltbridge.business.domain.billing.PricingType;
import com.voltbridge.business.domain.billing.TariffEffectiveRange;
import com.voltbridge.business.domain.billing.TariffPeriod;
import com.voltbridge.business.domain.billing.TariffPeriodType;
import com.voltbridge.business.domain.billing.TariffPlan;
import com.voltbridge.business.domain.billing.TariffPlanRepository;
import com.voltbridge.business.domain.billing.TariffPlanStatus;
import com.voltbridge.business.domain.billing.TariffRole;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.DeviceProtocol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TariffPlanLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void effectiveRangesAreHalfOpenAndNullEndMeansInfinity() {
        TariffEffectiveRange first = range("2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z");
        assertThat(first.overlaps(range("2026-05-01T00:00:00Z", "2026-08-01T00:00:00Z"))).isTrue();
        assertThat(first.overlaps(range("2026-07-01T00:00:00Z", "2026-12-01T00:00:00Z"))).isFalse();
        assertThat(range("2026-01-01T00:00:00Z", null).overlaps(range("2026-07-01T00:00:00Z", null))).isTrue();
        assertThat(first.contains(Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
        assertThat(first.contains(Instant.parse("2026-07-01T00:00:00Z"))).isFalse();
    }

    @Test
    void scheduledActivationRejectsOnlyScheduledOverlapAndAllowsAdjacentRanges() {
        InMemoryTariffs repository = new InMemoryTariffs();
        UUID tenant = UUID.randomUUID();
        TariffPlan fallback = fallback(tenant, NOW.minusSeconds(86400));
        repository.save(fallback);

        TariffPlan first = plan(tenant, "Scheduled A", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z");
        repository.save(first);
        ActivateTariffPlanUseCase activate = new ActivateTariffPlanUseCase(repository, () -> NOW);
        activate.execute(first.id());
        TariffPlan overlap = plan(tenant, "Scheduled B", "2026-05-01T00:00:00Z", "2026-08-01T00:00:00Z");
        repository.save(overlap);
        assertFailure("TARIFF_EFFECTIVE_RANGE_CONFLICT", () -> activate.execute(overlap.id()));

        TariffPlan adjacent = plan(tenant, "Scheduled C", "2026-07-01T00:00:00Z", null);
        repository.save(adjacent);
        assertThat(activate.execute(adjacent.id()).status()).isEqualTo(TariffPlanStatus.ACTIVE);
        assertThat(fallback.status()).isEqualTo(TariffPlanStatus.ACTIVE);
    }

    @Test
    void resolverPrioritizesScheduledAndReportsBothKindsOfAmbiguity() {
        InMemoryTariffs repository = new InMemoryTariffs();
        DefaultTariffResolver resolver = new DefaultTariffResolver(repository);
        UUID tenant = UUID.randomUUID();
        TariffPlan fallback = fallback(tenant, NOW.minusSeconds(86400));
        repository.save(fallback);
        assertThat(resolver.resolve(transaction(tenant, NOW))).isSameAs(fallback);

        TariffPlan scheduled = plan(tenant, "Scheduled", "2026-01-01T00:00:00Z", null);
        scheduled.activate(NOW);
        repository.save(scheduled);
        assertThat(resolver.resolve(transaction(tenant, NOW))).isSameAs(scheduled);

        TariffPlan corruptScheduled = plan(tenant, "Overlapping scheduled", "2026-05-01T00:00:00Z", null);
        corruptScheduled.activate(NOW);
        repository.save(corruptScheduled);
        assertFailure("TARIFF_EFFECTIVE_RANGE_AMBIGUOUS", () -> resolver.resolve(transaction(tenant, NOW)));

        UUID noFallbackTenant = UUID.randomUUID();
        assertFailure("FALLBACK_TARIFF_NOT_FOUND", () -> resolver.resolve(transaction(noFallbackTenant, NOW)));

        UUID duplicatedFallbackTenant = UUID.randomUUID();
        repository.save(fallback(duplicatedFallbackTenant, NOW.minusSeconds(86400)));
        repository.save(fallback(duplicatedFallbackTenant, NOW.minusSeconds(86400)));
        assertFailure("FALLBACK_TARIFF_AMBIGUOUS",
                () -> resolver.resolve(transaction(duplicatedFallbackTenant, NOW)));
    }

    @Test
    void scheduledRetirementUsesFallbackForNewTransactionsAndRetainsHistory() {
        InMemoryTariffs repository = new InMemoryTariffs();
        UUID tenant = UUID.randomUUID();
        TariffPlan fallback = fallback(tenant, NOW.minusSeconds(86400));
        TariffPlan scheduled = plan(tenant, "Current Scheduled", "2026-01-01T00:00:00Z", null);
        scheduled.activate(NOW.minusSeconds(3600));
        repository.save(fallback);
        repository.save(scheduled);
        RetireTariffPlanUseCase retire = new RetireTariffPlanUseCase(repository, () -> NOW);

        TariffPlan retired = retire.execute(scheduled.id());
        assertThat(retired.status()).isEqualTo(TariffPlanStatus.RETIRED);
        assertThat(retired.effectiveTo()).isEqualTo(NOW);
        assertThat(new DefaultTariffResolver(repository).resolve(transaction(tenant, NOW.minusSeconds(1))))
                .isSameAs(scheduled);
        assertThat(new DefaultTariffResolver(repository).resolve(transaction(tenant, NOW)))
                .isSameAs(fallback);
    }

    @Test
    void futureScheduledTariffCanBeRetired() {
        InMemoryTariffs repository = new InMemoryTariffs();
        UUID tenant = UUID.randomUUID();
        TariffPlan current = plan(tenant, "Current", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z");
        current.activate(NOW.minusSeconds(86400));
        repository.save(current);
        TariffPlan future = plan(tenant, "Future", "2026-07-01T00:00:00Z", null);
        future.activate(NOW.minusSeconds(3600));
        repository.save(future);

        TariffPlan retired = new RetireTariffPlanUseCase(repository, () -> NOW).execute(future.id());

        assertThat(retired.status()).isEqualTo(TariffPlanStatus.RETIRED);
        assertThat(current.status()).isEqualTo(TariffPlanStatus.ACTIVE);
    }

    @Test
    void newTenantInitializationIsIdempotentAndCreatesRequiredFixedFallback() {
        InMemoryTariffs repository = new InMemoryTariffs();
        UUID tenant = UUID.randomUUID();
        EnsureTenantFallbackTariffUseCase initialize = new EnsureTenantFallbackTariffUseCase(repository, () -> NOW);

        TariffPlan first = initialize.execute(tenant);
        TariffPlan repeated = initialize.execute(tenant);

        assertThat(repeated).isSameAs(first);
        assertThat(first.role()).isEqualTo(TariffRole.FALLBACK);
        assertThat(first.revision()).isOne();
        assertThat(first.status()).isEqualTo(TariffPlanStatus.ACTIVE);
        assertThat(first.pricingType()).isEqualTo(PricingType.FIXED);
        assertThat(first.currency()).isEqualTo("CNY");
        assertThat(first.periods()).singleElement().satisfies(period -> {
            assertThat(period.periodType()).isEqualTo(TariffPeriodType.FLAT);
            assertThat(period.startMinute()).isZero();
            assertThat(period.endMinute()).isEqualTo(1440);
            assertThat(period.energyPricePerKwh()).isEqualByComparingTo("1.00");
            assertThat(period.serviceFeePerKwh()).isEqualByComparingTo("0.20");
        });
        assertThat(new DefaultTariffResolver(repository).resolve(transaction(tenant, NOW))).isSameAs(first);
    }

    @Test
    void fallbackSwitchIsAtomicContiguousAndHistoricalTransactionsUseTheirRevision() {
        InMemoryTariffs repository = new InMemoryTariffs();
        UUID tenant = UUID.randomUUID();
        TariffPlan v1 = fallback(tenant, NOW.minusSeconds(86400));
        repository.save(v1);
        Instant switchAt = NOW.plusSeconds(3600);

        TariffPlan v2 = new CreateFallbackTariffRevisionUseCase(repository, () -> NOW)
                .execute(tenant, switchAt, new BigDecimal("1.10"), new BigDecimal("0.20"));

        assertThat(v1.status()).isEqualTo(TariffPlanStatus.RETIRED);
        assertThat(v1.effectiveTo()).isEqualTo(switchAt);
        assertThat(v1.retiredAt()).isEqualTo(switchAt);
        assertThat(v2.role()).isEqualTo(TariffRole.FALLBACK);
        assertThat(v2.revision()).isEqualTo(2);
        assertThat(v2.effectiveFrom()).isEqualTo(switchAt);
        assertThat(v2.effectiveTo()).isNull();
        DefaultTariffResolver resolver = new DefaultTariffResolver(repository);
        assertThat(resolver.resolve(transaction(tenant, NOW))).isSameAs(v1);
        assertThat(resolver.resolve(transaction(tenant, switchAt))).isSameAs(v2);
        assertThat(v2.periods().getFirst().energyPricePerKwh()).isEqualByComparingTo("1.10");
    }

    @Test
    void fallbackCannotBeRetiredOrDeletedAndDraftRevisionNumbersAreNeverReused() {
        InMemoryTariffs repository = new InMemoryTariffs();
        InMemoryBillingRecords bills = new InMemoryBillingRecords();
        UUID tenant = UUID.randomUUID();
        TariffPlan fallback = fallback(tenant, NOW.minusSeconds(86400));
        repository.save(fallback);
        assertFailure("FALLBACK_TARIFF_CANNOT_RETIRE",
                () -> new RetireTariffPlanUseCase(repository, () -> NOW).execute(fallback.id()));
        assertFailure("FALLBACK_TARIFF_CANNOT_DELETE",
                () -> new DeleteTariffPlanUseCase(repository, bills).execute(fallback.id()));

        TariffPlan v1 = plan(UUID.randomUUID(), "Family V1", "2026-01-01T00:00:00Z", null);
        repository.save(v1);
        CreateTariffRevisionUseCase revisions = new CreateTariffRevisionUseCase(repository, () -> NOW);
        TariffPlan v2 = revision(revisions, v1);
        TariffPlan v3 = revision(revisions, v1);
        assertThat(v2.role()).isEqualTo(TariffRole.SCHEDULED);
        assertThat(v3.revision()).isEqualTo(3);
        new DeleteTariffPlanUseCase(repository, bills).execute(v3.id());
        assertThat(revision(revisions, v1).revision()).isEqualTo(4);
    }

    @Test
    void genericRevisionCannotChangeFallbackRoleOrPricingModel() {
        InMemoryTariffs repository = new InMemoryTariffs();
        TariffPlan fallback = fallback(UUID.randomUUID(), NOW.minusSeconds(86400));
        repository.save(fallback);
        CreateTariffRevisionUseCase revisions = new CreateTariffRevisionUseCase(repository, () -> NOW);
        assertFailure("FALLBACK_REVISION_USE_CASE_REQUIRED", () -> revisions.execute(fallback.id(), "Fallback V2",
                "CNY", PricingType.TIME_OF_USE, "Asia/Shanghai", List.of(flat()), NOW, null));
    }

    private static TariffPlan revision(CreateTariffRevisionUseCase useCase, TariffPlan source) {
        return useCase.execute(source.id(), "Next", "CNY", PricingType.FIXED, "Asia/Shanghai",
                List.of(flat()), NOW, null);
    }

    private static TariffPlan plan(UUID tenantId, String name, String from, String to) {
        return TariffPlan.create(tenantId, name, "CNY", PricingType.FIXED, "Asia/Shanghai",
                List.of(flat()), Instant.parse(from), to == null ? null : Instant.parse(to), NOW);
    }

    private static TariffPlan fallback(UUID tenantId, Instant from) {
        return TariffPlan.createFallback(tenantId, UUID.randomUUID(), "默认固定资费", "CNY", "Asia/Shanghai",
                List.of(flat()), from, NOW);
    }

    private static TariffPeriod flat() {
        return new TariffPeriod(1, TariffPeriodType.FLAT, 0, 1440, BigDecimal.ONE, new BigDecimal("0.20"));
    }

    private static TariffEffectiveRange range(String from, String to) {
        return new TariffEffectiveRange(Instant.parse(from), to == null ? null : Instant.parse(to));
    }

    private static ChargingTransaction transaction(UUID tenantId, Instant startedAt) {
        ChargingTransaction transaction = ChargingTransaction.start(tenantId, UUID.randomUUID(), "DEVICE-1",
                UUID.randomUUID(), 1, DeviceProtocol.OCPP_16, UUID.randomUUID(), "ORDER-1", startedAt.minusSeconds(1));
        transaction.begin("protocol-transaction-1", startedAt, BigDecimal.ZERO, ChargingStartMethod.REMOTE, startedAt);
        return transaction;
    }

    private static void assertFailure(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(VoltBridgeException.class)
                .extracting(error -> ((VoltBridgeException) error).code()).isEqualTo(code);
    }

    private static final class InMemoryTariffs implements TariffPlanRepository {
        private final Map<UUID, TariffPlan> plans = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> revisionHighWaterMarks = new ConcurrentHashMap<>();

        @Override public void save(TariffPlan plan) {
            plans.put(plan.id(), plan);
            revisionHighWaterMarks.merge(plan.familyId(), plan.revision(), Math::max);
        }
        @Override public Optional<TariffPlan> findById(UUID id) { return Optional.ofNullable(plans.get(id)); }
        @Override public List<TariffPlan> findByTenantId(UUID tenantId) {
            return plans.values().stream().filter(plan -> plan.tenantId().equals(tenantId)).toList();
        }
        @Override public List<TariffPlan> findEffectiveAt(UUID tenantId, TariffRole role, Instant at) {
            return findByTenantId(tenantId).stream()
                    .filter(plan -> plan.role() == role && plan.effectiveAt(at)).toList();
        }
        @Override public List<TariffPlan> findActiveOverlapping(UUID tenantId, Instant from, Instant to,
                                                                 UUID excludingId) {
            TariffEffectiveRange candidate = new TariffEffectiveRange(from, to);
            return findByTenantId(tenantId).stream().filter(plan -> !plan.id().equals(excludingId)
                    && plan.role() == TariffRole.SCHEDULED && plan.status() == TariffPlanStatus.ACTIVE
                    && plan.effectiveRange().overlaps(candidate)).toList();
        }
        @Override public void lockTenantForTariffLifecycle(UUID tenantId) { }
        @Override public void lockFamilyForRevision(UUID familyId) { }
        @Override public int nextRevision(UUID familyId) {
            return revisionHighWaterMarks.merge(familyId, 1, Integer::sum);
        }
        @Override public boolean delete(UUID id) { return plans.remove(id) != null; }
    }

    private static final class InMemoryBillingRecords implements BillingRecordRepository {
        private final Set<UUID> referencedTariffs = ConcurrentHashMap.newKeySet();
        @Override public Optional<com.voltbridge.business.domain.billing.BillingRecord> findById(UUID id) { return Optional.empty(); }
        @Override public Optional<com.voltbridge.business.domain.billing.BillingRecord> findByTransactionId(UUID id) { return Optional.empty(); }
        @Override public boolean existsByTariffPlanId(UUID tariffPlanId) { return referencedTariffs.contains(tariffPlanId); }
        @Override public com.voltbridge.business.domain.billing.BillingRecord saveIfAbsent(
                com.voltbridge.business.domain.billing.BillingRecord record) { return record; }
    }
}
