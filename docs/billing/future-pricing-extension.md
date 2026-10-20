# 后续计费扩展路径

## 分时电价

后续可为 `PricingType` 新增 `TIME_OF_USE`，并在 `PricingEngine` 注册 `TimeOfUsePricingPolicy`。该策略必须消费按时间范围划分的真实电表/电量片段，而不是按总电量或充电时长比例虚构峰平谷电量。

需要新增的输入与配置包括：

- 带开始、结束、计量值或增量电量的时间序列片段；
- `PEAK`、`FLAT`、`VALLEY`、`OFF_PEAK` 时段定义及其单价；
- 时区、跨日和节假日规则；
- 在快照中保存实际命中时段、各段电量及单价。

现有 `PricingPolicy`、`PricingResult` 和 `BillingLineItem` 已能承载新增行项目，但当前没有存储时间序列，也没有实现分时计算。

## 会员折扣与营销

未来折扣应位于基础计价之后，而不是写进 `TariffPlan`：

```text
基础电费和服务费
    -> DiscountPolicy
    -> BillingAdjustment / 负向明细
    -> 最终账单金额
```

届时可增加会员等级、优惠券、促销或封顶规则，并通过新的负金额/调整类型明细解释金额变化。当前 `discountAmount` 固定为零；未实现会员、优惠券、促销或积分模型。

## 其他费用

停车费、会话费、空闲占用费和税费应作为独立的计价策略或账单调整产生新明细，不应改写既有 `ENERGY_FEE`、`SERVICE_FEE` 或历史账单。`BillingRecord` 保持不可变；如需更正，应设计显式的调整/修订单据，而不是静默重算。

## 支付与结算

支付应在后续独立的 `ChargingOrder` / `Payment` 领域处理：

```text
BillingRecord（应收金额） -> ChargingOrder / Payment（支付状态） -> Settlement（结算）
```

本 MVP 不包含钱包、支付网关、退款、对账、发票或结算，也不会向充电设备下发平台资费。

## 保持的边界

扩展应继续保持 Billing 在 Business 服务内：Gateway 负责协议连接与设备事件投递，协议适配器只规范化充电事实。不要为账单增加 Gateway RPC、设备计费报文或单独 Kafka 主题，除非未来真实业务边界发生变化。
