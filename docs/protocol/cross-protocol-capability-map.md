# 跨协议能力映射

| 平台能力 | 云快充 V1.5 | OCPP 1.6J | Canonical Model | 当前状态 |
|---|---|---|---|---|
| 登录/启动 | 0x01 登录 | BootNotification | `DeviceConnectedEvent` | 两者已支持 |
| 心跳 | 0x03 | Heartbeat | `HeartbeatEvent` | 两者已支持 |
| 枪口状态 | 0x1C 实时数据 | StatusNotification | `ConnectorStatusEvent` | 两者已支持 |
| 充电进度 | 0x1C 实时数据 | MeterValues | `ChargingProgressEvent` | 两者已支持 |
| 交易开始 | 0x31/0x32 StartCharge | StartTransaction | `DeviceTransactionStartRequest` | 两者均支持；云快充使用同步业务准入 |
| 交易完成 | 0x19/0x3B | StopTransaction | `ChargingEndedEvent`、`TransactionRecordEvent` | 两者已支持 |
| 远程启动 | 0x34/0x33 | RemoteStartTransaction | `StartChargingCommand`、`CommandResultEvent` | 两者已支持 |
| 远程停止 | 0x36/0x35 | RemoteStopTransaction | `StopChargingCommand`、`CommandResultEvent` | 两者已支持 |
| 校时 | 0x56/0x55 | Boot/Heartbeat 的 currentTime | `SynchronizeTimeCommand` | 云快充已实现；OCPP 独立下行操作未验证 |
| 设备重启 | 0x92/0x91 | Reset（来源未验证） | `ResetDeviceCommand` | 云快充已实现；OCPP 待核实 |
| 配置管理 | 0x52/0x51 工作参数 | Get/ChangeConfiguration（来源未验证） | 新配置子模型 | 均未支持 |
| 可用性/解锁 | 无已核实等价物 | ChangeAvailability/UnlockConnector（来源未验证） | 新规范命令 | 不进入当前批次 |

## 应保持协议专用的能力

- 云快充 BMS 原始协商、SPN/故障位、地锁帧：可规范化为诊断扩展事件，但不应污染通用交易状态机。
- 云快充离线卡、费率模型、OTA：需要卡账户、计费或固件发布子系统，不能伪装为启停命令。
- OCPP DataTransfer、Diagnostics、Firmware：在取得规范与产品需求前保持协议专用或不实现。
