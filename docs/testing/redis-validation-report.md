# Redis 真实验证报告

本报告记录 Redis 专项集成测试的边界。下方“未覆盖项”描述的是本报告所列测试本身；当前完整分布式场景另由 [`voltbridge-e2e`](distributed-e2e.md) 验证。

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

## 本报告未覆盖项

本报告中的 Redis 专项测试没有启动完整的 Business/Gateway Bootstrap 多进程，也没有在该部署下运行 TCP/WebSocket 模拟器、Kafka 回流和账单闭环；这些流程已由当前 Full Distributed E2E 覆盖。Gateway 强制崩溃后的 TTL 自动恢复和 Redis outage/chaos 仍未自动化，详见[已知限制](../release/known-limitations.md)。本报告不代表生产级故障演练或生产验收。
