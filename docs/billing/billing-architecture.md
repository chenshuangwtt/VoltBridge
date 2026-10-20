# VoltBridge 计费 MVP 架构

## 业务边界

`ChargingTransaction` 描述一次物理充电过程；`BillingRecord` 是该过程结束后计算出的不可变应收结果。账单不是支付、退款、结算、发票或订单，当前版本不会处理这些商业流程。

计费仅依赖 Business 中已规范化的交易，不依赖 YunKuaiChong、OCPP、TCP、WebSocket、Gateway 或 Kafka 设备事件类型。因此，两类协议都通过同一条业务主线计费：

```text
协议最终记录 / Kafka DeviceEvent
    -> Business 规范交易处理
    -> ChargingTransaction = COMPLETED
    -> ChargingTransactionCompletedEvent（事务提交后）
    -> BillCompletedTransactionUseCase
    -> TariffResolver + PricingEngine
    -> BillingRecord + BillingLineItem
```

在单体部署中，事件直接来自本地协议处理后的 Business 交易；在分布式部署中，Gateway 只投递设备事件，Business 消费后仍走相同的规范交易处理和内部计费监听器。Gateway、协议模块和设备下行均不依赖 Billing。

## 核心模型与职责

- `TariffPlan`：租户的固定费率方案，保存货币、电能单价、服务费单价、生效时间和状态。
- `TariffResolver`：按租户和 `ChargingTransaction.startedAt` 选择已启用且处于有效窗口的方案；它不使用账单执行时的系统时间。
- `PricingEngine` / `PricingPolicy`：根据方案类型选择显式策略。MVP 仅注册 `FixedRatePricingPolicy`，没有通用规则引擎。
- `BillingRecord`：已完成交易的一次不可变结算计算，保存金额、货币、交易时间、资费方案和 JSON 定价快照。
- `BillingLineItem`：可解释的明细。MVP 固定产生 `ENERGY_FEE` 与 `SERVICE_FEE` 两行。

`BillingRecord` 的 JSONB `pricing_snapshot` 保存资费 ID、版本、名称、货币、计价类型、两项单价和生效窗口。查询历史账单时不会重新解析“当前资费”，因此改价不会影响旧账单。

## 触发、失败与重试

`HandleTransactionRecordUseCase` 仅在最终记录首次成功应用且交易持久化后发布 `ChargingTransactionCompletedEvent`。`BillingTransactionCompletionListener` 在 `AFTER_COMMIT` 阶段调用账单用例，避免资费缺失或暂时性存储故障回滚已经确认的物理充电交易。

若交易未完成、缺少最终电量、缺少开始时间或找不到资费，账单不会伪造为零金额。监听器记录失败原因，运营人员补配资费或修复基础设施后可调用：

```text
POST /api/billing-records/by-transaction/{transactionId}/retry
```

该接口与事件重复投递共用同一个幂等用例；已存在账单时直接返回原账单。

## 幂等与并发

应用层先按 `transactionId` 查询已有账单；持久化层通过 `billing_record.transaction_id` 的唯一约束及 `saveIfAbsent` 处理竞争。多个 Business 实例、Kafka 至少一次投递或设备最终记录重传都只能得到一张账单，未使用进程内 `synchronized` 锁。

## 数据库与 API

现有项目使用 `voltbridge-infra-persistence/src/main/resources/db/schema-init.sql` 与 `schema_version` 维护增量结构，本次登记版本 `009`，新增：

- `tariff_plan`
- `billing_record`（`transaction_id` 唯一）
- `billing_line_item`

主要 API：

- `POST /api/tariffs`：创建固定资费草稿。
- `POST /api/tariffs/{tariffPlanId}/activate`：启用方案。
- `GET /api/tariffs?tenantId={tenantId}`、`GET /api/tariffs/{tariffPlanId}`：查询资费。
- `GET /api/billing-records/{billingRecordId}`、`GET /api/billing-records/by-transaction/{transactionId}`：查询可解释账单。

开发演示可显式创建 `energyPricePerKwh=0.80`、`serviceFeePerKwh=0.20`、`currency=CNY` 的方案；这些值只属于 API 配置或测试数据，未硬编码在生产计费逻辑中。
