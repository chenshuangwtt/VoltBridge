# REST API 概览

所有 API 由 Business/Monolith HTTP 服务提供，默认端口 `8080`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/devices/{deviceCode}` | 查询设备 |
| POST | `/api/devices/{deviceCode}/commands/start-charging` | 远程启动 |
| POST | `/api/devices/{deviceCode}/commands/stop-charging` | 远程停止 |
| POST | `/api/devices/{deviceCode}/commands/time-sync` | 时间同步 |
| POST | `/api/devices/{deviceCode}/commands/reset` | 设备复位 |
| GET | `/api/commands/{commandId}` | 查询命令状态 |
| GET | `/api/transactions/{transactionId}` | 查询交易 |
| GET | `/api/transactions` | 按查询条件列出交易 |
| POST/GET | `/api/tariffs`、`/api/tariffs/{id}` | 创建、查询资费 |
| POST | `/api/tariffs/{id}/activate` | 激活资费 |
| GET | `/api/billing-records/{id}` | 查询账单 |
| GET | `/api/billing-records/by-transaction/{id}` | 按交易查询账单 |

启动示例：

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/devices/20231212000010/commands/start-charging' -ContentType 'application/json' -Body '{"connectorNo":"01","limitAmount":50,"businessRef":"demo"}'
```

运行状态：`/actuator/health`；指标：`/actuator/metrics`、`/actuator/prometheus`。指标标签不应携带设备号、会话号、命令号等高基数标识。
