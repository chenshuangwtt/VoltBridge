# OCPP 1.6J 消息模型

## 线格式

- CALL：`[2, uniqueId, action, payload]`
- CALLRESULT：`[3, uniqueId, payload]`
- CALLERROR：`[4, uniqueId, errorCode, errorDescription, errorDetails]`

`Ocpp16FrameCodec` 统一校验顶层数组、长度、固定位置类型和对象 payload，动作处理器不重复解析顶层结构。内部类型为 `Ocpp16Call`、`Ocpp16CallResult`、`Ocpp16CallError`。

## 动作注册

`Ocpp16ActionHandlerRegistry` 按 action 名称注册处理器。协议入口不使用逐动作 `if/else`；新增动作只需实现 `Ocpp16ActionHandler` 并注册 Spring Bean。重复 action 在启动时失败。

## 错误策略

- 不支持 action：返回 `NotSupported` CALLERROR。
- 字段缺失、类型、时间或单位非法：返回 `PropertyConstraintViolation` 或 `FormationViolation`。
- JSON/数组结构非法：返回 `FormationViolation`。
- 内部异常：返回 `InternalError`，日志记录 sessionId，不回显堆栈给设备。
- 未知或超时 uniqueId 的 CALLRESULT/CALLERROR：告警并忽略，不关闭会话。

每个入站 CALL 的响应复用原 uniqueId；下行 CALL 的 uniqueId 只由协议关联表使用，不作为业务交易号。
