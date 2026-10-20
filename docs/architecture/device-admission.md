# 设备准入

云快充登录和 OCPP `BootNotification` 需要在协议响应前取得业务注册判断。Gateway 将标准 `DeviceConnectedEvent` 映射为 `DeviceAdmissionRequest`，通过 `DeviceAdmissionService.Authorize` 调用 Business。

Business 使用已有 `HandleDeviceConnectedUseCase` 检查设备是否已注册、是否禁用和协议是否匹配，再返回 `accepted/reason`。准入成功后 Gateway 才激活本地会话与 `deviceCode -> sessionId` 路由，并把连接事件发布到 Kafka。

Gateway 入网 RPC 默认超时两秒，并使用虚拟线程执行，避免阻塞 Netty I/O 线程。业务服务不可用、超时或调用异常时采取保守拒绝策略 `business_admission_unavailable`，设备不会无限等待。
