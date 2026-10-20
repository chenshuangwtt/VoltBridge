# 发布就绪审计

VoltBridge 当前适合以 **prototype / portfolio-grade backend** 公开发布。它不宣称 production-ready；以下差距是后续工程化路线图，而非阻止公开源码的事项。

## HIGH / ROADMAP

- 数据库初始化使用幂等 `schema-init.sql`，尚未采用受迁移工具管理的版本化脚本。
- 尚未完成 Business、两个 Gateway、设备模拟器、Kafka 事件回流与账单闭环的同部署全进程自动化验收。
- Redis 宕机、Gateway 强制终止和跨节点 Kafka 顺序尚未自动化。

## MEDIUM

- Maven Wrapper 未提交，需要 Maven 3.9+。
- REST API 未集成 OpenAPI；以 `docs/release/api.md` 和控制器为准。

## 已验证

- 公开 Git 历史、许可证、README、Maven 元数据和 GitHub Actions CI 已就绪。
- compose 的 PostgreSQL、Kafka 与 Redis 可启动，端口可环境变量覆盖。
- 空 PostgreSQL 可由应用初始化；真实 Redis 路由、TTL、围栏与动态 gRPC 路由通过。
