# 命令投递

1. REST 调用业务用例，创建并持久化 `DeviceCommand`。
2. 分布式 Business 的 `GrpcDeviceCommandGateway` 先解析 Redis `DeviceRoute` 与 `GatewayNode`，再把规范命令转换为 protobuf，调用拥有当前会话的 Gateway `DispatchCommand`。
3. Gateway `GatewayCommandGrpcService` 交给本地 `DeviceCommandDispatcher`：路由、会话、协议适配和传输写出均发生在 Gateway 本地。
4. gRPC 成功仅代表 Gateway 接受并写入发送责任；设备 ACK/拒绝必须等待 `CommandResultEvent` 经 Kafka 返回。

Gateway 不可达映射为 `GATEWAY_UNAVAILABLE`；路由过期、节点缺失或 Gateway 明确没有会话映射为 `STALE_ROUTE`；没有任何共享路由才是 `DEVICE_OFFLINE`。三者都不能被错误标记为设备响应超时。
