# OCPP 1.6J 覆盖

来源仅限 VoltBridge 的 `docs/protocol/ocpp16`、`voltbridge-protocol-ocpp16` 与测试。仓内未发现 OCPP 1.6J 规范全文或另一份 OCPP reference implementation；因此未出现于当前资料的 action 一律不猜测字段与支持细节。

| Action | Direction | Purpose | VoltBridge Status | VoltBridge Class | Canonical Mapping | Priority | Architecture Impact | Test Coverage | Notes |
|---|---|---|---|---|---|---|---|---|---|
| BootNotification | CP -> CS | 建立逻辑设备身份 | IMPLEMENTED | `BootNotificationHandler` | `DeviceConnectedEvent` + admission | CORE | LOW | WebSocket/scenario | 未认证会话受保护 |
| Heartbeat | CP -> CS | 保活/校时回应 | IMPLEMENTED | `HeartbeatHandler` | `HeartbeatEvent` | CORE | LOW | core + scenario | — |
| StatusNotification | CP -> CS | 枪口状态 | IMPLEMENTED | `StatusNotificationHandler` | `ConnectorStatusEvent` | CORE | LOW | core + scenario | — |
| StartTransaction | CP -> CS | 开始交易 | IMPLEMENTED | `StartTransactionHandler` | `DeviceTransactionStartRequest` | CORE | LOW | core + scenario | 本地和远程启动共用 |
| MeterValues | CP -> CS | 计量与进度 | IMPLEMENTED | `MeterValuesHandler` | `ChargingProgressEvent` | CORE | LOW | core + scenario | 支持单位归一 |
| StopTransaction | CP -> CS | 结束/最终交易 | IMPLEMENTED | `StopTransactionHandler` | `ChargingEndedEvent`、`TransactionRecordEvent` | CORE | LOW | core + scenario | — |
| RemoteStartTransaction | CS -> CP | 远程启动 | IMPLEMENTED | `Ocpp16ProtocolAdapter` | `StartChargingCommand` / result | CORE | LOW | encoder + WS/scenario | CALLRESULT 只代表接受 |
| RemoteStopTransaction | CS -> CP | 远程停止 | IMPLEMENTED | `Ocpp16ProtocolAdapter` | `StopChargingCommand` / result | CORE | LOW | encoder + WS/scenario | — |
| Authorize | CP -> CS | 令牌授权 | SOURCE_NOT_VERIFIED | — | 需独立授权模型 | RECOMMENDED | HIGH | spec + handler + auth scenario | 当前资料无 action 实现/规范来源 |
| Reset | CS -> CP | 软/硬重启 | SOURCE_NOT_VERIFIED | — | `ResetDeviceCommand` | RECOMMENDED | MEDIUM | encoder + result + integration | 待取得规范来源后确认 |
| UnlockConnector | CS -> CP | 解锁枪口 | SOURCE_NOT_VERIFIED | — | `UnlockConnectorCommand` | RECOMMENDED | MEDIUM | encoder + result + scenario | 待验证 |
| ChangeAvailability | CS -> CP | 启停用桩/枪 | SOURCE_NOT_VERIFIED | — | `ChangeAvailabilityCommand` | RECOMMENDED | MEDIUM | encoder + state scenario | 待验证 |
| Get/ChangeConfiguration | CS -> CP | 参数读取/更新 | SOURCE_NOT_VERIFIED | — | 配置子模型 | RECOMMENDED | HIGH | contract + persistence + integration | 待验证 |
| Diagnostics/Firmware/DataTransfer | 双向 | 运维/厂商扩展 | SOURCE_NOT_VERIFIED | — | 协议专用 | SKIP_FOR_NOW | HIGH | 专项基础设施 | 不能凭名称推断实现 |

汇总：已验证 inventory 为 8 个核心 action，全部 IMPLEMENTED；另有 6 组常见 action 仅作为 `SOURCE_NOT_VERIFIED` 候选，不进入覆盖率分母。测试缺口是没有 OCPP 分布式服务拆分场景及未实现运维 action 的规范测试。
