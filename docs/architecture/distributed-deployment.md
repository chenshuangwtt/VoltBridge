# 分布式部署

VoltBridge 同时支持单体与分布式两种组合方式。两种方式复用同一套 `DeviceCommand`、`DeviceEvent` 和业务处理器，区别只在端口的基础设施实现。

## 运行拓扑

```text
HTTP -> Business Service -> gRPC -> Gateway Service -> TCP / WebSocket -> 设备
                  ^                         |
                  |---------- Kafka --------|
```

- `voltbridge-monolith-bootstrap`：业务、网关、协议和传输均在一个 JVM；事件用内存同步分发，命令直接访问本地会话。
- `voltbridge-business-bootstrap`：REST、业务应用、PostgreSQL、Kafka 消费、Gateway gRPC 客户端及设备准入 gRPC 服务。
- `voltbridge-gateway-bootstrap`：本地设备会话、TCP、WebSocket、云快充、OCPP、Kafka 发布及命令 gRPC 服务。

多个 Gateway 可同时提供 TCP、WebSocket 与 gRPC 命令入口。Business 通过 Redis 解析 `deviceCode -> Gateway 路由 -> Gateway 节点`，再通过按节点复用的 gRPC Channel 下发命令；真实 socket 始终只属于目标 Gateway 本地。

## 本地启动

先执行 `docker compose -f deploy/docker-compose.yml up -d`，它会启动 PostgreSQL、Kafka 与 Redis。然后启动 Business，以及两个使用不同节点/端口配置的 Gateway。示例：

```powershell
$env:GATEWAY_NODE_ID='gateway-a'; $env:GATEWAY_GRPC_PORT='9090'; $env:GATEWAY_GRPC_ADVERTISED_PORT='9090'; mvn -pl voltbridge-gateway-bootstrap spring-boot:run
$env:GATEWAY_NODE_ID='gateway-b'; $env:GATEWAY_GRPC_PORT='9092'; $env:GATEWAY_GRPC_ADVERTISED_PORT='9092'; $env:TCP_PORT='9002'; $env:OCPP_WEBSOCKET_PORT='9102'; mvn -pl voltbridge-gateway-bootstrap spring-boot:run
```

两节点都应设置可被 Business 访问的 `GATEWAY_GRPC_ADVERTISED_HOST`；生产 TCP/WebSocket 入口通常交由现成的四层/七层负载均衡基础设施保持连接黏性，VoltBridge 不实现自定义负载均衡器。

数据库默认开发密码为 `123456`，生产环境必须通过 `DB_PASSWORD` 覆盖。
