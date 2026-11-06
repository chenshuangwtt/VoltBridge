# 本地开发数据

VoltBridge 提供一组只用于本地开发和 VoltBridge-Console 演示的固定数据。数据脚本位于 `voltbridge-infra-persistence/src/main/resources/db/`，不会由 Spring Boot、Flyway 或生产启动流程自动执行，也不属于正式 schema migration。

## 加载本地测试数据

从 VoltBridge 仓库根目录打开两个 PowerShell 终端。

1. 启动 Compose PostgreSQL：

   ```powershell
   docker compose -f .\deploy\docker-compose.yml up -d postgres
   ```

2. 在第一个终端启动 Monolith。Flyway 会在空数据库中创建 schema：

   ```powershell
   .\mvnw.cmd -pl voltbridge-monolith-bootstrap -am spring-boot:run
   ```

3. 等待 `http://localhost:8080/actuator/health` 返回 `UP`，然后在第二个终端手工执行开发 seed：

   ```powershell
   $composeFile = '.\deploy\docker-compose.yml'
   $postgresContainer = (docker compose -f $composeFile ps -q postgres).Trim()
   if (-not $postgresContainer) { throw 'Compose postgres container is not running' }

   docker cp .\voltbridge-infra-persistence\src\main\resources\db\dev-seed.sql "$($postgresContainer):/tmp/voltbridge-dev-seed.sql"
   if ($LASTEXITCODE -ne 0) { throw 'Could not copy dev-seed.sql into postgres' }

   docker compose -f $composeFile exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d voltbridge -f /tmp/voltbridge-dev-seed.sql
   if ($LASTEXITCODE -ne 0) { throw 'dev-seed.sql failed; psql reports the failing line' }
   ```

4. 在 `VoltBridge-Console/.env` 中设置以下值，然后刷新页面。仓库的 `.env.example` 已提供相同默认值：

   ```dotenv
   VITE_DEFAULT_TENANT_ID=11111111-1111-4111-8111-111111111111
   ```

该 seed 可重复执行：使用固定 ID，并只更新带有演示标识的同一行。PostgreSQL Testcontainers 自动测试会从空库迁移 V1，执行 seed 两次、执行 reset 后再 seed，并验证 DEMO、六台专用 Simulator 设备和外部租户数据。脚本不会创建任何测试用户或密码。

使用 docker cp 把 SQL 文件原样复制进容器，再以 psql -f 执行，可避免 PowerShell 通过管道传递文本时产生编码差异。Seed 中的中文也由自动测试校验为正确存储的 UTF-8 文本；终端显示成问号时，单凭显示结果不能判断 SQL 文件或数据库内容已经损坏。

## V1 baseline 调整后的本地数据库重建

V1 尚未冻结，baseline 字段调整后，执行过旧 V1 的本地开发库必须重新创建。不要在旧库上运行应用，也不要用 `flyway repair` 或手工 baseline 绕过 checksum。先停止所有 VoltBridge 后端进程，然后只删除并重建 Compose 中的 `voltbridge` 数据库：

```powershell
docker compose -f .\deploy\docker-compose.yml exec -T postgres dropdb -U postgres --if-exists voltbridge
docker compose -f .\deploy\docker-compose.yml exec -T postgres createdb -U postgres voltbridge
```

这只重建 PostgreSQL 中的 `voltbridge` 数据库，不删除 Compose volume，也不清除 Kafka/Redis 数据。完成后再启动 Monolith，由 Flyway 从空库执行 V1；需要演示数据时，再按上文手工执行 `dev-seed.sql`。此操作仅适用于可丢弃的本地开发数据。

## 演示数据

| 数据 | 数量 | 可用于查询的示例 |
| --- | ---: | --- |
| Tenant | 1 | `11111111-1111-4111-8111-111111111111` |
| DEMO Device | 8 | `VB-DEMO-001` 至 `VB-DEMO-008` |
| Simulator Device | 6 | `OCPP-SIM-001` 至 `OCPP-SIM-003`；YKC `20231212000010` 至 `20231212000012` |
| Device 总数 | 14 | 8 台 DEMO + 6 台 Simulator |
| DeviceCommand | 12 | `60000000-0000-4000-8000-000000000001` 至 `60000000-0000-4000-8000-000000000012` |
| ChargingTransaction | 20 | `70000000-0000-4000-8000-000000000001` 至 `70000000-0000-4000-8000-000000000020` |
| TariffPlan | 4 | 1 个 FALLBACK 固定全天价 V1、2 个固定 SCHEDULED 版本、Asia/Shanghai 峰谷 SCHEDULED DRAFT V1 |
| BillingRecord | 14 | `80000000-0000-4000-8000-000000000001` 至 `80000000-0000-4000-8000-000000000014` |
| Billing line item | 28 | 每张账单包含电能费和服务费两条 |

DEMO 设备覆盖 `YUN_KUAI_CHONG_150` 和 `OCPP_16`；命令状态覆盖 CREATED、SENT、ACKED、EXECUTING、SUCCEEDED、FAILED、TIMEOUT，其中 ACKED 不代表命令已 SUCCEEDED。20 笔 DEMO 交易覆盖 STARTING、CHARGING、ENDING、COMPLETED、START_FAILED、ABORTED。`VB-DEMO-006` 的 connector 01 保留为空闲连接器，用于 Console 手动测试启动充电。14 张 FINALIZED 账单只关联已完成交易，并使用标准固定费率方案：电能费 1.00 CNY/kWh、服务费 0.20 CNY/kWh。完整 Asia/Shanghai 峰平谷资费以 DRAFT 提供，避免与当前 ACTIVE 固定方案有效区间冲突。演示账单金额按后端规则分项四舍五入到分。

`VB-DEMO-*` 只用于 UI 演示快照，不会创建真实设备连接或 Gateway route。`OCPP-SIM-*` 与 14 位 YKC 设备码是专用协议联调设备：各有 connector 1，初始 `AVAILABLE`，不预置 DeviceCommand 或 ChargingTransaction。全部设备的业务状态只使用 `ENABLED` / `DISABLED`；在线会话只由模拟器或真实设备登录后写入 runtime store，seed 不会伪造在线状态。模拟器启动参数见 [设备模拟器文档](testing/device-simulator.md#available-simulator-devices)。

示例关联：交易 `70000000-0000-4000-8000-000000000003` 的账单是 `80000000-0000-4000-8000-000000000003`；该交易为 23.7 kWh，账单总额为 CNY 28.44。命令 `60000000-0000-4000-8000-000000000001` 为 CREATED，`...000003` 为 ACKED，`...000005` 和 `...000010` 为 SUCCEEDED，`...000008`、`...000011` 为 FAILED，`...000009`、`...000012` 为 TIMEOUT。

## 重置演示数据

以下脚本只删除固定开发租户、`VB-DEMO-%`、`DEMO-ORDER-%`、`DEMO-CMD-%` 以及六个固定 Simulator deviceCode 标识的数据；不清空表、不删除用户数据。若设备或连接器被其他交易、命令引用，相关行会保留，避免破坏外部关联。

```powershell
$composeFile = '.\deploy\docker-compose.yml'
$postgresContainer = (docker compose -f $composeFile ps -q postgres).Trim()
if (-not $postgresContainer) { throw 'Compose postgres container is not running' }

docker cp .\voltbridge-infra-persistence\src\main\resources\db\reset-dev-seed.sql "$($postgresContainer):/tmp/voltbridge-reset-dev-seed.sql"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy reset-dev-seed.sql into postgres' }

docker compose -f $composeFile exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d voltbridge -f /tmp/voltbridge-reset-dev-seed.sql
if ($LASTEXITCODE -ne 0) { throw 'reset-dev-seed.sql failed; psql reports the failing line' }
```

当前仓库的 reset 脚本名为 reset-dev-seed.sql（不存在 reset-dev-data.sql）。它按外键依赖顺序只删除固定演示租户及带演示标识的数据；如果演示行被非演示数据引用，会保留相关行。自动测试也会确认独立的测试租户和用户在 reset 后仍存在。重置后再次运行 dev-seed.sql 即可重新加载。
