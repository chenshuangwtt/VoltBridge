package com.voltbridge.device.model;

import java.util.UUID;

/** 设备主动开始交易后的标准业务决定及持久化协议流水。 */
public record DeviceTransactionStartResult(boolean accepted, long protocolTransactionId,
                                           UUID transactionId, String reason) {
}
