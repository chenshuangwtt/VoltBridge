# 建议的下一命令批次（仅建议，不自动实施）

目标是 6 个平台能力，不追求消息数量。

| 顺序 | Capability | 已核实协议落点 | Canonical Impact | Architecture | 最低测试 |
|---|---|---|---|---|---|
| 1 | 时间同步 | 云快充 0x56/0x55 | 新 `SynchronizeTimeCommand` + `CommandResultEvent` | MEDIUM | encoder/parser/correlation/TCP scenario |
| 2 | 设备重启 | 云快充 0x92/0x91 | 新 `ResetDeviceCommand` + result | MEDIUM | codec/handler/correlation/integration |
| 3 | BMS 故障诊断上报 | 云快充 0x1B、0x1D 等 | 新 `DeviceDiagnosticEvent`（扩展字段） | MEDIUM | parser/mapping/simulator fault scenario |
| 4 | 工作参数读写边界 | 云快充 0x52/0x51 | 新配置命令、业务配置模型 | HIGH | codec/persistence/auth/integration |
| 5 | OCPP Reset | 当前资料仅候选 | `ResetDeviceCommand` 复用 | MEDIUM | **先取得规范来源**，再写 encoder/result/WS scenario |
| 6 | OCPP Authorize | 当前资料仅候选 | 新授权端口/模型 | HIGH | **先取得规范来源**，再写授权与拒绝场景 |

实施顺序应先完成 1、2、3：它们有明确的现场运维价值和可验证协议行为。第 4-6 项必须先补齐产品需求与 OCPP 规范来源；不可因“常见 action”直接编码。

`ChangeAvailability`、`UnlockConnector`、`GetConfiguration`、`ChangeConfiguration` 暂不列入承诺批次：当前仓内 OCPP 来源不足，且云快充的对应能力不对称。OTA、离线卡、费率管理明确 `SKIP_FOR_NOW`，因为需要独立业务子系统。
