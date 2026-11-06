# FIXED 费率策略

FIXED 是统一版本化资费模型中的一种 `PricingType`。资费单价不再保存在 `tariff_plan` 顶层，而是保存在唯一的 `FLAT` `TariffPeriod`（`startMinute=0`、`endMinute=1440`）中。每日分时规则和真实计量样本要求见[计费架构](./billing-architecture.md)。

## 适用条件

仅当 `ChargingTransaction.status` 为 `COMPLETED` 时才能创建账单。可计费电量来自规范交易的最终 `energyKwh`，不从 YunKuaiChong 或 OCPP 协议字段重新计算，也不采用设备上报金额作为平台账单总额。

设备上报的 `totalAmount` 会继续保存在交易中，后续可用于对账；平台账单总额由规范电量与解析出的资费方案计算。

## 公式

```text
energyFee  = energyKwh × energyPricePerKwh
serviceFee = energyKwh × serviceFeePerKwh
subtotal   = energyFee + serviceFee
discount   = 0.00
total      = subtotal
```

示例资费为电能费 `0.80 CNY/kWh`、服务费 `0.20 CNY/kWh`，最终电量 `20.000000 kWh`：

```text
energyFee  = 16.00 CNY
serviceFee =  4.00 CNY
subtotal   = 20.00 CNY
discount   =  0.00 CNY
total      = 20.00 CNY
```

## 精度与舍入

`BillingDecimalPolicy` 是唯一的数值规则入口：

| 数据 | 精度 | 舍入方式 |
| --- | --- | --- |
| 电量 | 6 位小数 | `HALF_UP` |
| 单价 | 6 位小数 | `HALF_UP` |
| 金额 | 2 位小数 | `HALF_UP` |

计算使用 `BigDecimal`，不使用 `double` 或 `float`。负电量、负单价和负服务费会被拒绝；零电量产生两条零金额明细和零金额账单，语义保持明确且可追溯。

## 资费有效期与历史语义

按交易 `startedAt` 解析资费。Resolver 先匹配有效的 `SCHEDULED` 方案；恰好一条时使用该方案，没有命中时使用租户的 `FALLBACK` 版本。多个 `SCHEDULED` 命中会以歧义错误拒绝，不会任意选取一条。有效窗口满足：

```text
effectiveFrom <= startedAt < effectiveTo
```

空的 `effectiveTo` 表示持续有效。Fallback 是版本化、全天覆盖的 FIXED 资费，不能普通退役或删除；调价通过新增 revision 完成。Scheduled 资费可按生命周期规则退役；后续交易若没有 Scheduled 命中则自动回退。资费改价通过新 revision 完成，账单持久化定价快照，历史账单不会受后续版本影响。

## 账单状态与明细

数据库允许 `PENDING`、`FINALIZED`、`FAILED`，当前成功路径只创建 `FINALIZED` 账单；失败不会创建伪造的零账单。每张账单包括：

- `ENERGY_FEE`：数量为最终 kWh，单价为电能单价。
- `SERVICE_FEE`：数量为最终 kWh，单价为每 kWh 服务费。

`billing_record.transaction_id` 唯一，重复最终记录、Kafka 重投或人工重试均返回同一张账单。
