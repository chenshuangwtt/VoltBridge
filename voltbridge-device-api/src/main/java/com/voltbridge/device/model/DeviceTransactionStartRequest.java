package com.voltbridge.device.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 协议设备主动报告交易开始时提交给业务层的标准请求。 */
public record DeviceTransactionStartRequest(DeviceId deviceId, DeviceProtocol protocol,
                                            int connectorNo, Instant startedAt,
                                            BigDecimal startMeterKwh,
                                            ChargingStartMethod startMethod,
                                            Instant occurredAt) {
}
