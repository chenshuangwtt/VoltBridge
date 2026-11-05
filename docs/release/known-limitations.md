# 已知限制

- 仅覆盖 YunKuaiChong V1.5 和 OCPP 1.6J 的当前命令/事件子集；不支持 OCPP 2.x。
- 计费仅为固定单价 MVP；没有支付、退款、结算、会员、优惠券或分时计费。
- 没有 OTA、离线卡、跨地域 Gateway 路由或完整租户/SaaS 产品能力。
- 生产 TLS、认证授权、密钥管理、备份、监控告警和部署加固仍需设计与实施。
- 当前 Flyway V1 baseline 覆盖完整既有 schema；旧 `schema-init.sql` 数据库需要人工核对并显式 baseline，尚无专门的旧结构自动修复迁移。
- Full E2E profile 已提供 Business + 双 Gateway JVM、PostgreSQL/Kafka/Redis Testcontainers 与 YKC/OCPP simulator 的分布式充电及账单闭环；A→B 同设备接管及 A stale disconnect 后 B 路由保留已覆盖。Gateway 强制崩溃后的 TTL 自动恢复和 Redis outage/chaos 尚未自动化，属于后续非阻塞 chaos testing。
