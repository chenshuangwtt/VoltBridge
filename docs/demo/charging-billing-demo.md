# 充电与计费演示

单体场景由 `YunKuaiChongSimulatorScenarioIntegrationTest` 与 `Ocpp16SimulatorScenarioIntegrationTest` 覆盖：设备登录/Boot、远程启动、充电进度、自然完成或远程停止，最终断言 `ChargingTransaction=COMPLETED` 与 `BillingRecord=FINALIZED`。

手工演示时先创建并激活固定费率方案，再启动平台和 `ManualSimulatorMain`，调用 `/api/devices/{deviceCode}/commands/start-charging`，等待模拟器完成，最后查询 `/api/transactions/{transactionId}` 与 `/api/billing-records/by-transaction/{transactionId}`。

同一业务闭环也由 [Full Distributed E2E](../testing/distributed-e2e.md) 在 Business、两个独立 Gateway JVM、PostgreSQL、Kafka 和 Redis 上验收。本页是 prototype 演示指南，不代表生产故障演练或生产验收。
