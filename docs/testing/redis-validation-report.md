# Redis 真实验证报告

验证环境：Docker Desktop 的 Redis 7.4，`localhost:6379`；连接参数由 `redis.it.host`、`redis.it.port` 系统属性传入，生产代码未硬编码本机地址。

## 原有测试基线

| 测试 | 结果 | 实现类型 |
| --- | --- | --- |
| `CaffeineDeviceRouteDirectoryTest` | 通过 | 本地 Caffeine，不接 Redis |
| `GrpcDeviceCommandGatewayMultiNodeTest` | 通过 | 内存路由/节点表；启动两个 gRPC 假 Gateway |
| `GatewayCommandGrpcServiceTest` | 通过 | 单元测试 |

原有测试没有真实 Redis，也没有启动完整的两个 Gateway Bootstrap 进程。

## 新增真实 Redis 验证

`RedisRoutingIntegrationTest`（显式 `-Dredis.it.enabled=true`）通过 2 项：Hash 字段与键布局、`claim/find`、路由替换、A 对 B 的刷新/删除围栏、路由和节点 TTL 刷新/过期。

`RedisMultiGatewayRoutingIntegrationTest` 通过 3 项：Business 使用真实 Redis 查路由和节点后发送到两个动态端口 gRPC Gateway；YunKuaiChong/OCPP 均只到拥有者 B；A→B 重连后的迟到删除失败；A 节点 TTL 过期后显式拒绝，B 重连后恢复。

## 未覆盖项

未启动完整的 Business/Gateway Bootstrap 多进程，也未在该部署下跑 TCP/WebSocket 模拟器、Kafka 回流、账单闭环、Redis 宕机和真实网关进程强杀。这些是发布前阻塞验证项，不得将本报告理解为全链路生产验收。
