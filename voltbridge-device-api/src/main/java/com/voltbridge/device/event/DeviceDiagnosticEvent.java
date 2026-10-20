package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备上报的已规范化诊断事实。
 *
 * <p>category、severity 与 code 供跨协议告警和观测使用；protocolDetail 保留已验证的厂商字段解释，
 * 不向业务层泄漏原始字节数组。</p>
 */
public record DeviceDiagnosticEvent(UUID eventId, DeviceId deviceId, String connectorNo,
                                    String severity, String category, String code, String description,
                                    String protocolTransactionId, String protocolDetail,
                                    Instant occurredAt) implements DeviceEvent {
}
