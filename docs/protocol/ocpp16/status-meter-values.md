# 状态与计量映射

## StatusNotification

| OCPP status | VoltBridge `ConnectorStatus` |
| --- | --- |
| `Available` | `AVAILABLE` |
| `Preparing` | `PLUGGED` |
| `Charging` / `SuspendedEV` / `SuspendedEVSE` | `CHARGING` |
| `Finishing` | `CHARGE_COMPLETE` |
| `Reserved` | `RESERVED` |
| `Unavailable` | `UNAVAILABLE` |
| `Faulted` | `FAULT` |
| 未识别值 | `UNKNOWN` |

`connectorId > 0` 产生 `ConnectorStatusEvent`。`connectorId = 0` 视为充电点级通知，只回复成功，不创建数据库连接器 0。

## MeterValues

每个 `meterValue` 产生一个 `ChargingProgressEvent`；字段可稀疏，不要求每次同时存在全部测量值。

| Measurand | 输入单位 | 平台值 |
| --- | --- | --- |
| `Energy.Active.Import.Register` | `Wh` / `kWh` | `energyKwh`，Wh 除以 1000 |
| `Power.Active.Import` | `W` / `kW` | `outputPowerKw`，W 除以 1000 |
| `Voltage` | `V` | `outputVoltage` |
| `Current.Import` | `A` | `outputCurrent` |
| `SoC` | `Percent` | `stateOfCharge` 0–100 |

所有数值使用 `BigDecimal`。未消费的 sampled value 与原单位保存在 `additionalInfo`，不把 OCPP measurand 名称扩散到业务模型。携带 `transactionId` 时按协议流水定位交易；缺失时只更新连接器运行态，不伪造交易关联。
