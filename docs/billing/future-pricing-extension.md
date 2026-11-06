# 后续计费扩展路径

## 时段日历扩展

当前已支持每天 24 小时的 `PEAK`、`FLAT`、`VALLEY` 分时价格、IANA 时区、跨日边界切分和基于真实累计电表读数的局部插值。后续可以另行设计工作日/周末、节假日、尖峰或特殊费率日历；不能破坏已有 revision、meter timeline 和账单快照语义。

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
