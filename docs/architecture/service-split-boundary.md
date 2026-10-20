# 服务拆分边界

Business Service 拥有 REST、业务状态机、`DeviceCommand`/`ChargingTransaction` 持久化和 `charging_device` 注册信息。Gateway Service 只拥有活动连接、`DeviceSessionRegistry`、本地路由及协议编码；它不读取业务 PostgreSQL 表，也不持久化命令业务状态。

跨服务只允许规范语言：Business 到 Gateway 使用 `DeviceCommand`，Gateway 到 Business 使用 `DeviceEvent`。`YunKuaiChongLoginMessage`、OCPP CALL、Netty `Channel` 与 `TransportSession` 都不能越过服务边界。

网络信封定义在 `voltbridge-contract-proto`：`DeviceCommandEnvelope` 和 `DeviceEventEnvelope` 使用 `oneof`，并保留 `messageId`、`commandId`、`eventId`、设备标识、连接器标识、发生时间和关联字段。`DeviceProtoMapper` 是唯一的规范模型转换位置。
