# 发布检查清单

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 本地研究资料迁出仓库 | FAIL | 执行环境禁止写入工作区外目录，研究资料仍在 `docs/reference/jcpp` 等路径 |
| 无意外研究/代理工具字符串 | FAIL | 残余命中仅来自待迁出的研究资料 |
| 第三方来源审查 | MANUAL_REVIEW | 需项目权利人最终逐文件确认 |
| Apache-2.0 LICENSE | PASS | 根 `LICENSE` 为官方文本 |
| README 许可证与 Maven 元数据 | PASS | 已声明 Apache-2.0 |
| 密钥与仓库卫生 | PASS（当前树） | 配置仅含本地开发默认值 |
| CI | PASS（基础） | JDK 21 + Maven verify |
| REAL REDIS / 围栏 / TTL | PASS | 显式真实 Redis 集成测试通过 |
| MULTI-GATEWAY gRPC 路由 | PASS（受限） | 两个动态 gRPC Gateway + Redis；非完整 Bootstrap 链路 |
| DATABASE MIGRATION | FAIL | 仍为 `schema-init.sql`，非版本化迁移 |
| 分布式全链路 | FAIL | 未完成双 Gateway 进程、模拟器、Kafka、账单闭环 |
| `mvn clean verify` | 待最终复跑 | 本阶段配置/文档变更后执行 |
| 文档化演示 | MANUAL_REVIEW | 需项目权利人按 README 执行 |
| Git 历史审计 | MANUAL_REVIEW | 当前目录无 `.git`，无历史可审计 |
| 公开历史策略 | PASS | 建议从清理后的工作树创建新的初始公开提交 |

**结论：NOT READY FOR PUBLIC GITHUB。** 所有 `FAIL` 和人工来源审查完成前，不得标记为 READY。
