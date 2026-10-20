# Gateway 节点注册表

Gateway 节点注册键为：

```text
voltbridge:v1:gateway:node:{gatewayNodeId}
```

Hash 保存 `gatewayNodeId`、`nodeInstanceId`、`grpcHost`、`grpcPort`、`startedAt`、`lastHeartbeatAt`、`expiresAt`。`gatewayNodeId` 是配置的逻辑节点名；`nodeInstanceId` 是本次进程运行实例的围栏标识。

Gateway 启动时写入广告地址，随后按配置周期刷新。刷新和关闭删除使用 Lua 比较 `nodeInstanceId`：新进程覆盖同名旧节点后，旧进程恢复也无法刷新或删除新注册。Business 发现路由引用的节点不存在、TTL 过期或实例 ID 不一致时返回 `STALE_ROUTE`。

`grpcHost/grpcPort` 是广告地址，不等于监听绑定地址。Gateway 可监听 `0.0.0.0:9090`，同时广告 `gateway-a:9090`；默认 `localhost` 仅适用于本机开发，应在跨主机部署中明确设置 `GATEWAY_GRPC_ADVERTISED_HOST`。
