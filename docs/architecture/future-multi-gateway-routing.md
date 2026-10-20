# 历史：Phase 10A 单 Gateway 限制（已由 Phase 12 解决）

Phase 10A 曾只有一个活跃 Gateway，Business gRPC 客户端直接指向固定 endpoint。Phase 12 已以 Redis 路由租约、节点注册、会话 epoch 与节点实例围栏替代该限制；现行设计见 [多 Gateway 路由](multi-gateway-routing.md)。

后续水平扩展 Gateway 时，可在共享存储维护：

```text
deviceCode -> gatewayNodeId -> sessionId + connectionEpoch + protocol + lease
```

共享存储只保存可序列化路由元数据，绝不保存 Netty `Channel`、`TransportSession` 或任何 live socket。真实连接始终属于本地 Gateway 内存；Business 依据 `gatewayNodeId` 选择对应 Gateway 的命令入口。
