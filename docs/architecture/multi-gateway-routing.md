# 多 Gateway 分布式路由

## 目标与边界

多个 Gateway 可同时提供 TCP、WebSocket 与 gRPC 命令入口；Redis 仅保存短生命周期路由元数据和节点注册，任何 `TransportSession`、`DeviceSession`、Netty Channel 或 WebSocket 对象都只留在拥有连接的 Gateway 本地内存。

```text
Business -> Redis route -> Redis node -> node-aware gRPC channel -> owning Gateway -> local DeviceSession
```

业务聚合、设备注册、命令、交易和账单仍在 PostgreSQL/Business 内。Redis 路由存在不等于 Business 的逻辑在线投影；两者通常一致，但职责不同。

## 路由与围栏

`DeviceRoute` 包含 `deviceCode`、`gatewayNodeId`、`gatewayNodeInstanceId`、`sessionId`、`connectionEpoch`、协议和租约时间。`connectionEpoch` 是会话 epoch；`gatewayNodeInstanceId` 是 Gateway 每次进程启动生成或显式配置的围栏 ID。

节点名可以稳定，例如 `gateway-a`；同名重启必须使用新的 `nodeInstanceId`。因此旧进程只能刷新/删除自己声明过的路由，不能复活或删除新进程的节点、会话和 TTL。

## 登录、重连与断开

1. 物理连接先只创建本地 `DeviceSession`。
2. 协议登录或 BootNotification 经 Business 准入成功后，Gateway 才激活会话。
3. Gateway 用 Lua 原子 `claim` 声明 Redis 路由；新有效会话立即成为权威会话（最新有效会话胜出）。
4. 同节点内可关闭被替换的旧本地会话；跨节点不要求同步关闭。
5. 断开、心跳刷新和定期刷新都以 `gatewayNodeId + nodeInstanceId + sessionId + epoch` 比较所有权。

因此 `A/session-1 -> B/session-2 -> A 的迟到断开` 时，A 的条件删除失败，B 的路由保持有效。业务在线态仍通过带 `sessionId` 的连接/断开事件更新，旧 session 的离线事件无法覆盖新 session 的在线态。

## 命令路径与失败语义

Business 的 `DeviceCommandGateway` 保持不变，分布式实现执行：路由查询、租约检查、节点查询、实例围栏检查、节点级 gRPC 客户端复用，最后调用目标 Gateway。目标 Gateway 再次验证本地会话、设备、协议和 epoch，Redis 不是活连接存在性的证明。

- `DEVICE_OFFLINE`：没有共享路由。
- `STALE_ROUTE`：路由过期、节点过期/缺失/实例不一致，或目标明确没有该会话。
- `GATEWAY_UNAVAILABLE`：目标 gRPC 连接失败。
- `TRANSPORT_SEND_FAILED`：Gateway 拥有会话但写入传输失败。
- `DEVICE_RESPONSE_TIMEOUT`：已写入设备后，协议响应超时；由既有命令生命周期处理。

目标 Gateway 明确返回 session 不存在时，Business 仅重新读取路由一次；只有所有权已变化才向新节点重发相同 `commandId`。不会对模糊的网络写入结果无限重试。Gateway 还会在短保留期内按 `commandId` 去重，同一节点上的 RPC 重放共享一次本地下发结果。

## TTL、健康与故障

默认路由与节点 TTL 为 90 秒、刷新间隔为 30 秒，均可用环境变量覆盖。Gateway 定期刷新所有 ACTIVE 本地会话的路由，同时刷新自己的节点注册。进程崩溃时不依赖优雅回调：路由和节点都会自然过期。

Redis 故障时，既有 socket 不会被 Redis 主动关闭，但新路由声明、刷新和 Business 寻址都会以 `ROUTE_REGISTRY_UNAVAILABLE` 显式失败；不会猜测或回退到任意 Gateway。单体使用 `routing.mode=local` 与 Caffeine，不需要 Redis。
