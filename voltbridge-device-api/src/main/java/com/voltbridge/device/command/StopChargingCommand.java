package com.voltbridge.device.command;

import com.voltbridge.device.model.ConnectorId;
import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台发往设备网关的协议无关停充命令。
 *
 * <p>同时携带平台交易标识和协议流水，便于不同协议选择所需字段；命令本身不包含
 * 云快充命令字、BCD、CRC 或网络会话信息。</p>
 */
public record StopChargingCommand(
        UUID commandId,
        DeviceId deviceId,
        ConnectorId connectorId,
        String connectorNo,
        UUID transactionId,
        String protocolTransactionId,
        Instant createdAt) implements DeviceCommand {
}
