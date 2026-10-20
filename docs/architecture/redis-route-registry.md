# Redis 设备路由注册表

## 键与序列化

键由 `RedisRoutingKeys` 统一生成，当前命名空间为：

```text
voltbridge:v1:route:device:{deviceCode}
```

每个键是 Redis Hash，字段为 `deviceCode`、`gatewayNodeId`、`gatewayNodeInstanceId`、`sessionId`、`connectionEpoch`、`protocol`、`authenticatedAt`、`lastSeenAt`、`leaseExpiresAt`。时间使用 ISO-8601 字符串，数值使用十进制字符串；不使用 Java 原生序列化。

## 原子操作

`RedisDeviceRouteDirectory` 使用 Lua 完成下列操作：

- `claim(route)`：一次 `HGETALL + HSET + PEXPIRE`，返回替换前路由。
- `touch(...)`：只有四元所有权全部匹配时才更新 `lastSeenAt`、`leaseExpiresAt` 和 TTL。
- `remove(...)`：只有四元所有权全部匹配时才删除键。

这避免了“先 GET 再 SET/DEL”的竞态。Redis 键 TTL 是 Gateway 崩溃后的最终清理保障，字段中的到期时间同时让 Business 进行确定性校验。

## 运行模式

- `voltbridge.routing.mode=redis`：Business 与 Gateway 都装配 Redis 注册表。
- `voltbridge.routing.mode=local`：单体使用 `CaffeineDeviceRouteDirectory`，没有 Redis Bean 或 Redis 健康依赖。

Redis 不是业务数据库；不要在此保存 `DeviceCommand`、`ChargingTransaction`、账单或业务在线状态。
