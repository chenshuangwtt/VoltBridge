package com.voltbridge.business.application.billing;

import java.time.Instant;
import java.util.UUID;

/** 业务内部事件：规范充电交易已持久化为 COMPLETED；不是设备协议或 Kafka 事件。 */
public record ChargingTransactionCompletedEvent(UUID transactionId, Instant completedAt) {
}
