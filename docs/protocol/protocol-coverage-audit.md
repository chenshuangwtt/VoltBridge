# 协议覆盖审计

审计日期：2026-10-19。范围仅限当前仓库的云快充 V1.5 与 OCPP 1.6J，以及只读参考项目 `D:\study\JChargePointProtocol`。本审计不改变运行时行为。

## 结论

- VoltBridge 已覆盖两协议的登录/启动、心跳、枪口状态、交易开始、实时计量、交易结束、远程启停和命令结果主链。
- 云快充参考实现可见 **42 个 V1.5 handler**；VoltBridge 直接实现其中 12 个通信方向能力（含应答），其余主要是费率、时间、重启、离线卡、OTA、地锁和 BMS 细节。
- VoltBridge OCPP 已实现 8 个主链 action；仓内没有完整 OCPP 规范副本，因此其余标准 action 统一标为 `SOURCE_NOT_VERIFIED`，不以记忆补全清单。
- 下一批应以跨协议能力组织：时间同步、设备重启、可用性控制、枪口解锁、配置查询/变更；云快充仅在已有 V1.5 可验证等价物时实施。

状态含义：`IMPLEMENTED`=已解析/编码、路由、规范映射与必要应答齐全；`PARTIAL`=主链存在但无独立端到端场景；`MISSING`=源码可验证而 VoltBridge 无实现；`SOURCE_NOT_VERIFIED`=当前来源不足。

详细清单见：`yunkuaichong-v150-coverage.md`、`ocpp16-coverage.md`、`cross-protocol-capability-map.md`。

## Phase 8.7 更新

- 云快充 `0x31/0x32` 已从部分链路升级为设备主动开充的异步业务准入与完整应答编码；不创建伪造的远程启动命令。
- 云快充 `0x56/0x55` 已实现为 `SynchronizeTimeCommand`，`0x55` 作为确定完成结果。
- 云快充 `0x92/0x91` 已实现为 `ResetDeviceCommand`，`0x91` 是重启成功的权威依据；之后的断连不改变命令结果。
- 已实现经参考代码验证的 `0x1B` BMS 充电错误映射到 `DeviceDiagnosticEvent`；`0x1D` 等其余 BMS 帧仍保持后续范围，避免依据参考实现中的字段读取缺陷推断协议语义。
- 跨服务契约已增加对时、重启和诊断事件的 protobuf oneof；命令继续走 gRPC，事件继续走 Kafka。
