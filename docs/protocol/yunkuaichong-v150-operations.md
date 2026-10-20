# 云快充 V1.5 运行操作

## 设备主动开充（0x31 / 0x32）

- `0x31` 为设备上行请求，固定 51 字节：桩编码（7 字节 BCD）、枪号（1 字节 BCD）、启动方式、密码标志、物理卡号（8 字节小端）、密码和 VIN。
- 协议处理器只解析和校验已认证会话；它将 `DeviceTransactionStartRequest` 与携带原始序列号/加密标记的应答器交给网关。
- 网关使用受管异步执行器调用 `DeviceTransactionStartService`：单体调用本地业务用例，分布式模式调用 Business 的 gRPC 准入服务，均不会阻塞 Netty EventLoop。
- `0x32` 固定 44 字节：交易流水（16 字节 BCD）、桩编码、枪号、逻辑卡号、余额（分，4 字节小端）、鉴权标志和失败原因。应答复用 `0x31` 的协议序列号和加密标记。
- 该路径没有预先存在的 `DeviceCommand`；关联边界是当前 TCP session、deviceCode、connectorNo 和业务创建的 `ChargingTransaction.id`，不会伪造平台远程启动命令。

## 对时（0x56 / 0x55）

- 平台通过 `SynchronizeTimeCommand` 下发 UTC `Instant`；云快充编码器在配置时区（默认 `Asia/Shanghai`）转换为 CP56Time2a，生成 `0x56`：桩编码（7 字节 BCD）+ CP56Time2a（7 字节）。
- `0x55` 使用相同的 14 字节布局作为设备完成结果。由于报文无唯一请求号，待处理表按 `commandType + deviceCode + connectorNo=0` 保存最新活动对时命令；同一设备的业务命令幂等键避免并发冲突。
- `0x55` 是确定的完成证据，发布 `CommandResultEvent(SUCCEEDED)`；业务状态机会先记录 ACK 再在同一事件中进入 `SUCCEEDED`。

## 重启（0x92 / 0x91）

- 平台通过 `ResetDeviceCommand` 下发。`whenIdle=false` 编码为大端整数 `1`（立即），`true` 编码为 `2`（空闲后），与 7 字节桩编码组成 `0x92`。
- `0x91` 为 8 字节：桩编码和一个结果字节，`0x01` 表示成功。其成功结果发布 `SUCCEEDED`，失败发布 `FAILED/DEVICE_REJECTED`。
- 重启成功后的连接断开是预期副作用：`0x91` 才是权威成功依据；断开事件既不会回写成功，也不会把已完成重启改判为失败。

## BMS 诊断（0x1B）

- 已实现参考项目可验证的 `0x1B` BMS 充电错误上行，固定 32 字节：交易流水、桩编码、枪号和八个 2-bit 状态字节。
- 转换为 `DeviceDiagnosticEvent`：`severity=ERROR`、`category=BMS_COMMUNICATION`、`code=YKC_BMS_CHARGING_ERROR`；逐位解释保存在 `protocolDetail`，不暴露原始报文字节，不改变充电交易状态。
- 事件通过单体内存发布或分布式 Kafka 的同一 `DeviceEvent` protobuf 信封投递；业务侧仅做结构化日志与观测，不创建额外诊断持久化子系统。

## 跨服务与边界

- Business 到 Gateway 的 `SynchronizeTimeCommand` 与 `ResetDeviceCommand` 使用既有 `GatewayRpcService.DispatchCommand` 和 `DeviceCommandEnvelope` oneof。
- Gateway 到 Business 的 `DeviceDiagnosticEvent` 使用既有 Kafka 事件通道和 `DeviceEventEnvelope` oneof。
- 本批次不包含定价、离线卡、OTA、完整工作参数、OCPP Reset 或其他 OCPP 操作。
