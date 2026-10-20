# 充电与计费演示

单体场景由 `YunKuaiChongSimulatorScenarioIntegrationTest` 与 `Ocpp16SimulatorScenarioIntegrationTest` 覆盖：设备登录/Boot、远程启动、充电进度、自然完成或远程停止，最终断言 `ChargingTransaction=COMPLETED` 与 `BillingRecord=FINALIZED`。

手工演示时先创建并激活固定费率方案，再启动平台和 `ManualSimulatorMain`，调用 `/api/devices/{deviceCode}/commands/start-charging`，等待模拟器完成，最后查询 `/api/transactions/{transactionId}` 与 `/api/billing-records/by-transaction/{transactionId}`。

当前没有为多 Gateway 部署完成同一链路的全进程自动化验收，不能把本页视为分布式生产验收记录。
