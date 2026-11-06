# Versioned tariff and billing architecture

## Scope

`ChargingTransaction` records one physical charging session. A finalized `BillingRecord` is an immutable amount due for that completed transaction; it does not represent payment, refund, settlement, tax invoice, parking, or reservation fees. YKC and OCPP events are normalized by Business before billing, so pricing has no protocol or Gateway dependency.

```text
protocol progress/final record
    -> normalized ChargingTransaction + ChargingMeterSample
    -> ChargingTransactionCompletedEvent (after commit)
    -> BillCompletedTransactionUseCase
    -> TariffResolver(transaction.startedAt)
    -> PricingEngine(FIXED | TIME_OF_USE)
    -> immutable BillingRecord + line items + pricing snapshot
```

## Tariff identity and lifecycle

A tariff plan has a stable `familyId`, a business `revision`, and a `TariffRole`, independent of the persistence optimistic-lock `version`. The pair `(familyId, revision)` is unique, and every revision in a family has the same tenant and role. `SCHEDULED` is the operator-managed role for fixed or time-of-use plans. Each tenant also has one database-backed `FALLBACK` family containing only fixed, all-day flat revisions. Pricing inputs (timezone, periods, prices, currency, and effective range) are copied into a new revision when prices change; an existing revision is never edited in place. A bill points to the concrete `tariffPlanId` revision selected when the transaction began.

The effective range is half-open: `[effectiveFrom, effectiveTo)`; a null `effectiveTo` means positive infinity. Activation serializes on the tenant and rejects overlap with another active `SCHEDULED` range using `TARIFF_EFFECTIVE_RANGE_CONFLICT`; adjacent ranges are valid. The resolver queries `SCHEDULED` first at `ChargingTransaction.startedAt`. Exactly one match wins; multiple matches fail with `TARIFF_EFFECTIVE_RANGE_AMBIGUOUS`. With no scheduled match, it resolves exactly one `FALLBACK` revision at the same transaction start time. Missing and ambiguous fallback data fail with `FALLBACK_TARIFF_NOT_FOUND` and `FALLBACK_TARIFF_AMBIGUOUS`; the resolver never picks an arbitrary latest row or invents a price. A transaction keeps its starting revision even when its charging interval crosses a later tariff revision's effective date.

`RETIRED` plans remain available to historical billing and cannot be reactivated. Any `ACTIVE` scheduled revision may be retired; retirement closes a currently effective range at the retirement instant. If no scheduled tariff matches a subsequent transaction, billing resolves the tenant's `FALLBACK` revision. `DRAFT` plans may have overlapping ranges and can be deleted only when no bill references them. `ACTIVE` and `RETIRED` plans cannot be deleted. Fallback revisions cannot use ordinary activate, retire, delete, or generic revision operations; changing fallback prices uses its dedicated atomic revision operation. The system does not require future scheduled ranges to cover infinity.

Fallback revisions form one contiguous chain per tenant: each prior revision ends exactly when the next starts, the old row is marked retired at that boundary, and exactly one active revision has an open end. Creating a tenant's initial fallback is handled by `EnsureTenantFallbackTariffUseCase`; this repository currently has no tenant-creation application flow, so future tenant creation must call the initializer inside its tenant transaction. The development seed also supplies the fallback for demo tenants.

## Daily periods and pricing types

Both pricing types use `TariffPeriod` rows with `sequenceNo`, `periodType`, `startMinute`, `endMinute`, energy price, and service fee. Minute bounds are local-day minutes (`00:00 = 0`, `24:00 = 1440`). Periods must be ordered, gap-free, non-overlapping, and cover `[0, 1440]`.

- `FIXED`: exactly one `FLAT` period from 0 to 1440.
- `TIME_OF_USE`: one or more `PEAK`, `FLAT`, or `VALLEY` periods covering the complete local day.

`TariffPlan.timezone` must be an IANA `ZoneId` such as `Asia/Shanghai`; no fixed UTC offset is assumed. The local tariff periods repeat each calendar day in that zone. Current rules do not include weekday/holiday variants, sharp-peak pricing, member pricing, or other fees.

## Meter evidence and time-of-use allocation

`HandleTransactionProgressUseCase` first resolves the device event to a real transaction. Only events with non-null cumulative `energyKwh` are saved as `ChargingMeterSample`; the protocol layer does not write billing storage. The event UUID is the idempotency key, so Kafka redelivery inserts the sample once. Other high-frequency telemetry remains in runtime state.

TOU billing builds an ordered timeline from the transaction's real start/end meter readings and persisted samples within its start/end times. It requires start and end anchors, non-decreasing cumulative readings, and agreement between the meter delta and final transaction energy within the centralized `BillingDecimalPolicy.METER_ENERGY_TOLERANCE` (0.001 kWh). It never invents meter readings. Missing or inconsistent evidence fails with `METER_TIMELINE_MISSING`, `METER_READING_NON_MONOTONIC`, or `METER_ENERGY_MISMATCH`; operators can use the existing billing retry endpoint after correcting/replaying evidence.

Each adjacent pair of real readings defines a local interval. If a tariff boundary lies inside that interval, VoltBridge linearly interpolates the cumulative reading at that boundary and assigns the resulting local energy deltas to the periods on either side. Long intervals are split at every crossed daily boundary, including midnight, using the tariff `ZoneId`. This is interpolation bounded by actual neighboring readings; it is not whole-transaction time-ratio allocation. Small precision residuals are reconciled into the final applied segment only within the central tolerance and are recorded in the pricing snapshot.

FIXED billing needs only the transaction's final energy. FIXED and TOU use the same centralized decimal policy: energy and unit price are stored at six decimals; each fee line is rounded to two currency decimals with `HALF_UP`. Each occupied TOU period produces an `ENERGY_FEE` and `SERVICE_FEE` line. The sum of lines must equal the billing subtotal and total.

## Immutable billing evidence

The JSON `pricing_snapshot` is serialized from a typed DTO with Jackson. It contains the selected role (`FALLBACK` or `SCHEDULED`), concrete tariff ID/family/revision, name, currency, type, timezone, effective range, every tariff period, applied period energies and prices, fee amounts, totals, and any meter reconciliation. `BillingRecord.tariffPlanId` references the concrete revision. `BillingRecord` and its line items are immutable after finalization; new tariff revisions cannot change old bills.

## API

- `POST /api/tariffs`: create a `SCHEDULED` `DRAFT` fixed or TOU plan. The request does not accept a role.
- `POST /api/tariffs/{tariffPlanId}/revisions`: create the next immutable `DRAFT` in a scheduled source family; fallback changes use the dedicated API.
- `POST /api/tariffs/{tariffPlanId}/activate`: validate and activate, rejecting overlaps with active `SCHEDULED` plans only.
- `POST /api/tariffs/{tariffPlanId}/retire`: retire an active scheduled revision; subsequent transactions with no matching scheduled tariff resolve to fallback.
- `DELETE /api/tariffs/{tariffPlanId}`: delete an unreferenced scheduled `DRAFT`; fallback revisions can never be deleted.
- `GET /api/tenants/{tenantId}/fallback-tariff?effectiveAt={instant}`: resolve the tenant fallback at the requested instant, defaulting to platform time.
- `POST /api/tenants/{tenantId}/fallback-tariff/revisions`: atomically switch fallback at `switchAt` with new fixed energy and service prices.
- `GET /api/tariffs?tenantId={tenantId}` and `GET /api/tariffs/{tariffPlanId}`: return revision metadata and all periods.
- `GET /api/billing-records/{billingRecordId}` and `GET /api/billing-records/by-transaction/{transactionId}`: retrieve the immutable bill.

OpenAPI is generated from the backend DTOs and documents the period bounds and prices.

## Persistence and local fixtures

`V1__baseline.sql` contains tariff role, retirement boundaries, the revision high-water table, and indexes for one fallback family and one open-ended fallback revision per tenant. Flyway is the only schema initialization path; the current baseline deliberately contains the complete schema, with no V2 migration. Development plans and billing examples live in the explicit `dev-seed.sql` fixture, never in Flyway migrations. The seed includes a fallback and scheduled plans for the demo tenant. See [database migrations](../deployment/database-migrations.md) and [the billing demo](../demo/charging-billing-demo.md).

## Completion, idempotency, and retry

The completion listener runs after the transaction record commits, so a billing failure cannot roll back a physical charging completion. Repeated completion events and concurrent callers use `billing_record.transaction_id` uniqueness and `saveIfAbsent`; an existing bill is returned unchanged. A missing tariff or invalid TOU meter timeline does not create a zero-value bill. Operators can retry through `POST /api/billing-records/by-transaction/{transactionId}/retry` after resolving the cause.
