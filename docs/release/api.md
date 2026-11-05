# REST API

Business Service and Monolith expose the same business REST API on HTTP port `8080` by default. Gateway has no public business REST API and does not include Swagger UI.

After starting Business or Monolith:

- Swagger UI: `http://localhost:8080/swagger-ui.html` (redirects to `/swagger-ui/index.html`)
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health and operational metrics remain under `/actuator/*` and are not included in the public business API document.

The OpenAPI document is titled **VoltBridge API**, version `0.1.0`, licensed under Apache License 2.0. It describes the prototype API and makes no production-readiness claim.

## Endpoint inventory

All successful controller responses currently use HTTP `200` with `ApiResponse<T>`:

```json
{"success":true,"code":"OK","message":"success","data":{}}
```

| Method | Path | Request / parameters | Response data | Success / error status |
| --- | --- | --- | --- | --- |
| GET | `/api/devices/{deviceCode}` | Path: `deviceCode` | `DeviceView` | 200 / 400 |
| POST | `/api/devices/{deviceCode}/commands/start-charging` | Path: `deviceCode`; JSON `StartChargingRequest` | `CommandCreatedView` | 200 / 400 |
| POST | `/api/devices/{deviceCode}/commands/stop-charging` | Path: `deviceCode`; JSON `StopChargingRequest` | `CommandCreatedView` | 200 / 400 |
| POST | `/api/devices/{deviceCode}/commands/time-sync` | Path: `deviceCode`; optional JSON `TimeSyncRequest` | `CommandCreatedView` | 200 / 400 |
| POST | `/api/devices/{deviceCode}/commands/reset` | Path: `deviceCode`; optional JSON `ResetDeviceRequest` | `CommandCreatedView` | 200 / 400 |
| GET | `/api/commands/{commandId}` | Path: UUID `commandId` | `CommandView` | 200 / 400 |
| GET | `/api/transactions/{transactionId}` | Path: UUID `transactionId` | `TransactionView` | 200 / 400 |
| GET | `/api/transactions` | Optional query: `deviceCode`, `connectorNo`, `status` | `TransactionView[]` | 200 / 400 |
| POST | `/api/tariffs` | JSON `CreateTariffRequest` | `TariffPlanView` | 200 / 400 |
| GET | `/api/tariffs/{tariffPlanId}` | Path: UUID `tariffPlanId` | `TariffPlanView` | 200 / 400 |
| GET | `/api/tariffs` | Required query: UUID `tenantId` | `TariffPlanView[]` | 200 / 400 |
| POST | `/api/tariffs/{tariffPlanId}/activate` | Path: UUID `tariffPlanId` | `TariffPlanView` | 200 / 400 |
| GET | `/api/billing-records/{billingRecordId}` | Path: UUID `billingRecordId` | `BillingRecordView` | 200 / 400 |
| GET | `/api/billing-records/by-transaction/{transactionId}` | Path: UUID `transactionId` | `BillingRecordView` | 200 / 400 |
| POST | `/api/billing-records/by-transaction/{transactionId}/retry` | Path: UUID `transactionId`; no body | `BillingRecordView` | 200 / 400 |

The `400` response uses the standard envelope with `success=false`, `data=null`, and a code/message. Domain and validation failures use `VoltBridgeException` codes or `VALIDATION_ERROR`; malformed bodies, invalid path/query parameters, and missing required query parameters use `INVALID_REQUEST`. Unhandled server failures and framework routing errors retain Spring Boot's default error response; they are not represented as `ApiResponse`.

## Asynchronous command lifecycle

Remote start returns a `commandId` after a DeviceCommand has been created and dispatched. HTTP success or command creation does not mean that charging has started. Read `GET /api/commands/{commandId}` to follow command state:

`CREATED` → `SENT` → `ACKED` → `EXECUTING` → `SUCCEEDED`, with terminal alternatives `FAILED` and `TIMEOUT`. `SENT` means the transport write succeeded; device acknowledgement and charging evidence are separate events.

Remote stop also returns a command identifier. Device acceptance does not mean the ChargingTransaction is finalized. Follow the command and then query `GET /api/transactions/{transactionId}` (or `GET /api/transactions`) until the platform transaction reaches its final state.

ChargingTransaction is a platform-level lifecycle, independent of protocol details:

`STARTING` → `CHARGING` → `ENDING` → `COMPLETED`.

Other terminal states include `START_FAILED` and `ABORTED`. Protocol-specific diagnostics are retained in `additionalInfo` and are not used as the generic lifecycle.

## Request examples

### StartCharging

```json
{
  "connectorNo": "01",
  "limitAmount": 50.00,
  "logicalCardNo": "1234567890123456",
  "physicalCardNo": "1234567890123456",
  "businessRef": "fleet-2026-001"
}
```

### StopCharging

```json
{"connectorNo":"01"}
```

### Create a fixed-rate tariff

```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "name": "Standard DC tariff",
  "currency": "CNY",
  "energyPricePerKwh": 1.00,
  "serviceFeePerKwh": 0.20,
  "effectiveFrom": "2026-11-05T00:00:00Z",
  "effectiveTo": null
}
```

## Response examples

### Command query

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {
    "commandId": "55555555-5555-4555-8555-555555555555",
    "deviceCode": "20231212000010",
    "connectorNo": "01",
    "type": "START_CHARGING",
    "status": "ACKED",
    "businessRef": "fleet-2026-001",
    "createdAt": "2026-11-05T02:00:00Z",
    "sentAt": "2026-11-05T02:00:01Z",
    "ackedAt": "2026-11-05T02:00:02Z",
    "completedAt": null,
    "failureCode": null,
    "failureReason": null
  }
}
```

### Transaction query

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {
    "id": "66666666-6666-4666-8666-666666666666",
    "deviceCode": "20231212000010",
    "connectorNo": 1,
    "protocol": "YUN_KUAI_CHONG_150",
    "protocolTransactionId": "52587579537158979080118985592381",
    "businessRef": "fleet-2026-001",
    "status": "COMPLETED",
    "startedAt": "2026-11-05T02:00:02Z",
    "endedAt": "2026-11-05T02:30:00Z",
    "startMeterKwh": 10.0,
    "endMeterKwh": 15.0,
    "energyKwh": 5.0,
    "totalAmount": 6.0,
    "additionalInfo": "{}"
  }
}
```

### BillingRecord query

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {
    "billingRecordId": "77777777-7777-4777-8777-777777777777",
    "transactionId": "66666666-6666-4666-8666-666666666666",
    "status": "FINALIZED",
    "currency": "CNY",
    "energyKwh": 5.0,
    "energyFee": 5.0,
    "serviceFee": 1.0,
    "subtotalAmount": 6.0,
    "discountAmount": 0.0,
    "totalAmount": 6.0,
    "tariffPlanId": "88888888-8888-4888-8888-888888888888",
    "pricingSnapshot": "{\"pricingType\":\"FIXED\",\"energyPricePerKwh\":1.0,\"serviceFeePerKwh\":0.2}",
    "lineItems": [],
    "chargingStartedAt": "2026-11-05T02:00:02Z",
    "chargingEndedAt": "2026-11-05T02:30:00Z",
    "finalizedAt": "2026-11-05T02:30:01Z"
  }
}
```

## Billing scope

Billing is a fixed-rate MVP. The record reports energy fee, service fee, total, line items, a tariff-plan relationship, and the pricing snapshot used for the result. `FINALIZED` records are immutable. This API does not provide payment, refund, settlement, membership, coupon, or time-of-use pricing operations.

## Build commands

Use the repository Maven Wrapper; a globally installed Maven is not required. Java 21 is still required.

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -Pfull-e2e verify
```

```sh
./mvnw clean verify
./mvnw -Pfull-e2e verify
```
