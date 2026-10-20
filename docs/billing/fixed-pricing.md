# 固定费率计价规则

## 适用条件

仅当 `ChargingTransaction.status` 为 `COMPLETED` 时才能创建账单。可计费电量来自规范交易的最终 `energyKwh`，不从 YunKuaiChong 或 OCPP 协议字段重新计算，也不采用设备上报金额作为平台账单总额。

设备上报的 `totalAmount` 会继续保存在交易中，后续可用于对账；本 MVP 的平台账单总额始终由规范电量与解析出的资费方案计算。

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

MVP 按交易 `startedAt` 解析 `tenantId` 下已 `ACTIVE` 的有效方案，窗口满足：

```text
effectiveFrom <= startedAt < effectiveTo
```

空的 `effectiveTo` 表示持续有效。资费改价必须新建方案，而不是原地修改已启用方案的费率。账单还会持久化定价快照，保证历史查询不会受到新方案影响。

## 账单状态与明细

数据库允许 `PENDING`、`FINALIZED`、`FAILED`，当前成功路径只创建 `FINALIZED` 账单；失败不会创建伪造的零账单。每张账单包括：

- `ENERGY_FEE`：数量为最终 kWh，单价为电能单价。
- `SERVICE_FEE`：数量为最终 kWh，单价为每 kWh 服务费。

`billing_record.transaction_id` 唯一，重复最终记录、Kafka 重投或人工重试均返回同一张账单。
