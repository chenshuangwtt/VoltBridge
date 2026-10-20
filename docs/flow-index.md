# VoltBridge 流程索引

下表用于从业务现象快速定位代码。类名均为当前实现中的真实入口；同一流程存在协议分支时，以 `/` 分隔。

| 流程 | 入口类/方法 | 主要应用类 | 网关类 | 协议类 | 传输类 | 持久化/状态类 |
|---|---|---|---|---|---|---|
| 应用启动 | `VoltBridgeApplication.main` | `VoltBridgeCoreConfiguration` | `LocalGatewayConnectionCoordinator` | `ProtocolRegistry` | `NettyTcpServer.start` / `NettyWebSocketServer.start` | Spring 装配各仓储与状态存储 |
| YKC 登录 | TCP 0x01 | `HandleDeviceConnectedUseCase` | `LocalGatewayConnectionCoordinator.onFrame/authenticate` | `YunKuaiChongProtocolAdapter` → `YunKuaiChongLoginHandler` | `TcpChannelHandler.channelRead0` | `MybatisChargingDeviceRepository` / `CaffeineDeviceRouteDirectory` / `CaffeineDeviceRuntimeStateStore` |
| OCPP Boot | `BootNotification` CALL | `HandleDeviceConnectedUseCase` | `LocalGatewayConnectionCoordinator.onFrame/authenticate` | `Ocpp16ProtocolAdapter` → `BootNotificationHandler` | `WebSocketHandshakeHandler` → `WebSocketFrameHandler` | `MybatisChargingDeviceRepository` / `CaffeineDeviceRouteDirectory` / `CaffeineDeviceRuntimeStateStore` |
| 心跳 | YKC 心跳 / OCPP `Heartbeat` | `HandleHeartbeatUseCase` | `DeviceEventDispatcher` | `YunKuaiChongHeartbeatHandler` / `HeartbeatHandler` | `TcpChannelHandler` / `WebSocketFrameHandler` | `CaffeineDeviceRuntimeStateStore` |
| 枪口状态 | YKC 状态/实时数据 / OCPP `StatusNotification` | `HandleConnectorStatusUseCase`、`HandleTransactionConnectorStatusUseCase` | `DeviceEventDispatcher` | `YunKuaiChongRealtimeDataHandler` / `StatusNotificationHandler` | TCP / WebSocket 帧处理器 | `CaffeineConnectorRuntimeStateStore` / `MybatisChargingTransactionRepository` |
| 远程启动 | `DeviceCommandController` | `StartChargingUseCase` | 单体：`DeviceCommandDispatcher`；分布式：`GrpcDeviceCommandGateway` → 目标 `DeviceCommandDispatcher` | `YunKuaiChongRemoteStartEncoder` / `Ocpp16ProtocolAdapter.encode` | `TransportSession.send` | PostgreSQL 命令/交易 + Redis route/node registry |
| 启动响应 | YKC 0x33 / OCPP CALLRESULT | `HandleCommandResultUseCase`、`HandleTransactionCommandResultUseCase` | `DeviceEventDispatcher` | `YunKuaiChongRemoteStartResultHandler` / `Ocpp16PendingRequestRegistry` | TCP / WebSocket 帧处理器 | `MybatisDeviceCommandRepository` / `MybatisChargingTransactionRepository` |
| 远程停止 | `DeviceCommandController` | `StopChargingUseCase` | 单体：`DeviceCommandDispatcher`；分布式：`GrpcDeviceCommandGateway` → 目标 `DeviceCommandDispatcher` | `YunKuaiChongRemoteStopEncoder` / `Ocpp16ProtocolAdapter.encode` | `TransportSession.send` | PostgreSQL 命令/交易 + Redis route/node registry |
| 停止响应 | YKC 0x35 / OCPP CALLRESULT | `HandleCommandResultUseCase` | `DeviceEventDispatcher` | `YunKuaiChongRemoteStopResultHandler` / `Ocpp16PendingRequestRegistry` | TCP / WebSocket 帧处理器 | `MybatisDeviceCommandRepository` |
| 充电进度 | YKC 实时数据 / OCPP `MeterValues` | `HandleChargingProgressUseCase`、`HandleTransactionProgressUseCase`、`ChargingStartedService` | `DeviceEventDispatcher` | `YunKuaiChongRealtimeDataHandler` / `MeterValuesHandler` | TCP / WebSocket 帧处理器 | `CaffeineConnectorRuntimeStateStore` / `MybatisChargingTransactionRepository` / `MybatisDeviceCommandRepository` |
| 交易开始 | YKC 首次真实充电数据 / OCPP `StartTransaction` | `ChargingStartedService` / `StartDeviceTransactionUseCase` | `DeviceEventDispatcher` | `YunKuaiChongRealtimeDataHandler` / `StartTransactionHandler` | TCP / WebSocket 帧处理器 | `MybatisChargingTransactionRepository` / `PostgreSqlProtocolTransactionIdAllocator` |
| 充电结束 | YKC 0x19 / OCPP `StopTransaction` | `HandleChargingEndedUseCase` / `HandleTransactionRecordUseCase` | `DeviceEventDispatcher` | `YunKuaiChongEndChargeHandler` / `StopTransactionHandler` | TCP / WebSocket 帧处理器 | `MybatisChargingTransactionRepository` / `MybatisDeviceCommandRepository` |
| 最终交易记录 | YKC 0x3B | `HandleTransactionRecordUseCase` | `LocalGatewayConnectionCoordinator` 同步分发后发送 ACK | `YunKuaiChongTransactionRecordHandler` | `TcpChannelHandler` | `MybatisChargingTransactionRepository` / `MybatisDeviceCommandRepository` |
| 命令超时 | `CommandTimeoutService.checkExpiredCommands` | `CommandTimeoutService` | 无 | 无 | 无 | `MybatisDeviceCommandRepository` |
| 连接断开 | Netty `channelInactive` | `HandleDeviceDisconnectedUseCase` | `LocalGatewayConnectionCoordinator.onDisconnected` | 无协议分支 | `TcpChannelHandler` / `WebSocketFrameHandler` | `CaffeineDeviceRouteDirectory` / `CaffeineDeviceRuntimeStateStore` |

## 状态判断口诀

- `SENT` 看传输写入，`ACKED` 看协议接受，`SUCCEEDED` 看真实业务效果。
- `TransportSession` 是连接，`DeviceSession` 是节点内设备会话，`DeviceRoute` 是可查询路由元数据。
- 协议响应通过 YKC 交易流水或 OCPP `uniqueId` 关联命令；交易最终通过 `protocolTransactionId` 关联领域交易。
- YKC 结束通知先进入 `ENDING`，最终交易记录才进入 `COMPLETED`；OCPP `StopTransaction` 自带最终数据，可直接完成交易。
# 当前阶段补充：分布式路由与账单

1. 设备登录/Boot：协议处理器 → `LocalGatewayConnectionCoordinator` → Redis 路由声明。
2. 远程启动/停止：REST Use Case → `GrpcDeviceCommandGateway` → Redis 路由与节点 → Gateway gRPC → 本地 `DeviceSession` → 协议编码。
3. 上行事件：Gateway → Kafka（按 deviceCode 键）→ Business → `ChargingTransaction` → `BillingRecord`。
4. 重连：B 声明新路由；A 的迟到刷新/断开因实例、会话与 epoch 不匹配而失败。
