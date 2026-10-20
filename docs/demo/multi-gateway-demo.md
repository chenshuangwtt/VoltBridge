# 多 Gateway 演示

1. 按根 README 的“分布式 / 多 Gateway”启动 PostgreSQL、Kafka、Redis、Business、Gateway A 与 Gateway B。
2. 让设备先连 A；执行 `redis-cli HGETALL 'voltbridge:v1:route:device:{设备号}'`，确认 `gatewayNodeId` 为 A。
3. 断开后让同一设备连 B；再次读取该 Hash，确认 `gatewayNodeId`、`gatewayNodeInstanceId`、`sessionId` 与 `connectionEpoch` 已变为 B 会话。
4. 调用 REST `start-charging`；Business 读取 Redis 路由并连接 B 的已公告 gRPC 地址，B 的日志应包含同一 `deviceCode`/`commandId`，A 不应发送该命令。
5. 模拟 A 的迟到断开；Redis 路由仍指向 B，因为删除 Lua 脚本比较节点、实例、会话和 epoch。

可重复的真实 Redis/gRPC 自动化覆盖见 `RedisMultiGatewayRoutingIntegrationTest`；完整双 Bootstrap 协议模拟器演示尚属发布前待办。
