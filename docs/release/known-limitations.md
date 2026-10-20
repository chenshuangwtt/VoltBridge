# 已知限制

- 仅覆盖 YunKuaiChong V1.5 和 OCPP 1.6J 的当前命令/事件子集；不支持 OCPP 2.x。
- 计费仅为固定单价 MVP；没有支付、退款、结算、会员、优惠券或分时计费。
- 没有 OTA、离线卡、跨地域 Gateway 路由或完整租户/SaaS 产品能力。
- 生产 TLS、认证授权、密钥管理、备份、监控告警和部署加固仍需设计与实施。
- 版本化数据库迁移仍待实现；当前使用幂等 `schema-init.sql`。
- Business、双 Gateway、模拟器、Kafka 与账单的全进程自动化 E2E 仍待完成；Redis 宕机验证也尚未自动化。
