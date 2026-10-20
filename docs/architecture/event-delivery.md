# 设备事件投递

Gateway 使用 `KafkaDeviceEventPublisher` 把规范事件写入唯一主题 `voltbridge.device.events`。Kafka key 固定为 `deviceCode`，因此同一设备的状态、进度、结束和最终交易事件进入同一分区，保留该设备内顺序；不同设备之间不承诺全局顺序。

每个事件在 Gateway 生成稳定 `eventId`，Kafka 重试不会重建事件。Business 的 `KafkaDeviceEventConsumer` 解码后调用与单体模式相同的 `InMemoryDeviceEventPublisher` 和 `DeviceEventHandler`，业务逻辑不依赖 Kafka。

消费者采用 record acknowledgement：业务处理正常返回后才提交 offset。异常使用三次指数退避（0.5、1、2 秒），仍失败则投递 `<topic>.DLT`。这提供 at-least-once 语义，命令结果、结束事件和最终交易处理必须利用既有命令/交易标识做幂等。

生产者配置 `acks=all`、幂等生产和五次重试；发送是异步操作，不在 Netty event loop 上等待 Kafka broker。持续发送失败会记录错误并可由 Micrometer 指标/日志告警。
