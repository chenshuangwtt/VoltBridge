# 设备模拟器场景矩阵

| 协议 | 场景 | 传输 | 启动方式 | 结束方式 | 预期 DeviceCommand | 预期 ChargingTransaction | 自动化/手工 | 状态 |
|---|---|---|---|---|---|---|---|---|
| YunKuaiChong V1.5 | remote start -> natural completion | TCP 二进制帧 | 平台 `0x34` | 目标 SOC，`0x19` + `0x3B` | START: `CREATED -> SENT -> ACKED -> SUCCEEDED` | `STARTING -> CHARGING -> ENDING -> COMPLETED` | 自动化 + 手工 | 已实现 |
| YunKuaiChong V1.5 | remote start -> remote stop | TCP 二进制帧 | 平台 `0x34` | 平台 `0x36`，设备 `0x35`，后续 `0x19` + `0x3B` | START 成功；STOP: `CREATED -> SENT -> ACKED -> SUCCEEDED` | `STARTING -> CHARGING -> ENDING -> COMPLETED` | 自动化 + 手工 | 已实现 |
| YunKuaiChong V1.5 | duplicate final record | TCP 二进制帧 | 平台 `0x34` | 重复 `0x3B` | START 成功 | 一笔交易，重复记录仍 ACK | 自动化 | 已实现 |
| YunKuaiChong V1.5 | disconnect during charging | TCP 二进制帧 | 平台 `0x34` | 连接断开 | START 已成功 | 保持 `CHARGING`，不伪造完成 | 自动化 | 已实现 |
| OCPP 1.6J | remote start -> natural completion | WebSocket `ocpp1.6` | RemoteStartTransaction | StopTransaction | START: `CREATED -> SENT -> ACKED -> SUCCEEDED` | `STARTING -> CHARGING -> COMPLETED` | 自动化 + 手工 | 已实现 |
| OCPP 1.6J | remote start -> remote stop | WebSocket `ocpp1.6` | RemoteStartTransaction | RemoteStop Accepted 后 StopTransaction | START 成功；STOP: `CREATED -> SENT -> ACKED -> SUCCEEDED` | `STARTING -> CHARGING -> COMPLETED` | 自动化 + 手工 | 已实现 |
| OCPP 1.6J | local start -> natural completion | WebSocket `ocpp1.6` | StartTransaction | StopTransaction | 不创建平台命令 | `CHARGING -> COMPLETED` | 自动化 | 已实现 |
