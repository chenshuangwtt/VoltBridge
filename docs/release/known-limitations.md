# 已知限制

- 仅覆盖 YunKuaiChong V1.5 和 OCPP 1.6J 的当前命令/事件子集；不支持 OCPP 2.x。
- Billing 目前支持版本化固定费率和每日峰/平/谷分时费率；TOU 依赖完整、单调且与最终电量一致的真实 meter timeline。没有支付、退款、结算、会员、优惠券、节假日或工作日资费规则。
- 没有 OTA、离线卡、跨地域 Gateway 路由或完整租户/SaaS 产品能力。
- 生产 TLS、认证授权、密钥管理、备份、监控告警和部署加固仍需设计与实施。
- 当前 V1 baseline 是正式初始化 schema；使用此前版本 V1 的本地开发数据库需按迁移指南重建，生产/共享数据库不适用此重置方式。
- Full E2E profile 已提供 Business + 双 Gateway JVM、PostgreSQL/Kafka/Redis Testcontainers 与 YKC/OCPP simulator 的分布式充电及账单闭环；A→B 同设备接管及 A stale disconnect 后 B 路由保留已覆盖。Gateway 强制崩溃后的 TTL 自动恢复和 Redis outage/chaos 尚未自动化，属于后续非阻塞 chaos testing。
