# 发布就绪审计

VoltBridge 当前适合以 **prototype / portfolio-grade backend** 公开发布。它不宣称 production-ready；以下差距是后续工程化路线图，而非阻止公开源码的事项。

## HIGH / ROADMAP

- 当前 V1 baseline 是正式初始化 schema；使用此前版本 V1 的本地开发库须按迁移指南删除并重新创建 `voltbridge` 数据库，不做旧 schema 自动兼容。
- Full E2E profile 已实现 Business、两个 Gateway JVM、协议模拟器、Kafka 事件回流和账单闭环；A→B 同设备接管与 A stale disconnect 后 B 路由保留已覆盖。
- Gateway 强制崩溃后的 TTL 自动恢复及 Redis outage/chaos 尚未自动化；跨节点 Kafka 顺序的额外边界仍需后续覆盖。

## MEDIUM

- Maven Wrapper 固定 Maven 3.9.9；本地仍需 Java 21。
- OpenAPI 文档仅覆盖 Business/Monolith 的 REST API；Gateway 没有公开业务 HTTP API。
- OpenAPI schema 与 Swagger UI 描述当前 REST API；端点语义和示例见 `docs/release/api.md`。

## 已验证

- 公开 Git 历史、许可证、README、Maven 元数据和 GitHub Actions CI 已就绪。
- compose 的 PostgreSQL、Kafka 与 Redis 可启动，端口可环境变量覆盖。
- 空 PostgreSQL 已由 Flyway Testcontainers 验证初始化和重启不重复执行；真实 Redis 路由、TTL、围栏与动态 gRPC 路由通过。
- GitHub Actions 配置 verify 与 distributed-e2e 两个 job，均使用 Maven Wrapper；后者运行 `./mvnw -Pfull-e2e verify`。
