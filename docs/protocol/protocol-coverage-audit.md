# 协议覆盖概览

本文描述当前 VoltBridge 可验证的协议能力，不改变运行时行为。

## 当前范围

- YunKuaiChong V1.5：登录、心跳、枪口状态、交易开始、充电进度、交易结束、远程启停、对时、重启和已实现的诊断事件。
- OCPP 1.6J：`BootNotification`、`Heartbeat`、`StatusNotification`、`StartTransaction`、`MeterValues`、`StopTransaction`、`RemoteStartTransaction` 和 `RemoteStopTransaction`。
- 两种协议均映射到统一设备命令、事件、`ChargingTransaction` 和 Billing MVP 主链。

## 状态含义

- `IMPLEMENTED`：解析或编码、路由、规范映射与必要应答齐全。
- `PARTIAL`：主链存在，但没有独立端到端场景。
- `MISSING`：当前尚未实现。

后续协议扩展以明确产品需求、公开协议规范和可复现测试为前提；不以记忆推测字段或行为。详细能力说明见 `ocpp16-coverage.md` 与 `cross-protocol-capability-map.md`。
