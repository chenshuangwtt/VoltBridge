# 发布就绪审计

## BLOCKER

- 当前数据库初始化使用幂等 `schema-init.sql`，尚未采用受迁移工具管理的版本化脚本。
- 尚未完成 Business、两个 Gateway、设备模拟器、Kafka 事件回流与账单闭环的同部署全进程验收。

## HIGH

- CI 运行 Maven 构建；真实 Redis 验证通过显式开关执行，尚未接入容器化服务测试。
- Redis 宕机、Gateway 强制终止和跨节点 Kafka 顺序尚未自动化。

## MEDIUM

- Maven Wrapper 未提交，需要 Maven 3.9+。
- REST API 没有 OpenAPI 文档；以 `docs/release/api.md` 和控制器为准。

## 已验证

- compose 的 PostgreSQL、Kafka 与 Redis 可启动，端口可环境变量覆盖。
- 空 PostgreSQL 可由应用初始化；真实 Redis 路由、TTL、围栏与动态 gRPC 路由通过。
