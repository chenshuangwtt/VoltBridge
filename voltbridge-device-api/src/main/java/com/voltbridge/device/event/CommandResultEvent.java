package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

/**
 * 协议命令结果的统一事件，业务层据此推进 DeviceCommand 状态。
 *
 * <p>标准失败代码用于业务判断，原始结果码和失败码用于诊断厂商协议差异。</p>
 */
public record CommandResultEvent(UUID eventId, DeviceId deviceId, UUID commandId,
                                 String correlationId, String protocolTransactionId,
                                 String connectorNo, String commandType,
                                 CommandResultStatus status, String failureCode, String failureReason,
                                 Integer rawResultCode, Integer rawFailureCode,
                                 boolean duplicate, Instant occurredAt) implements DeviceEvent {

    public CommandResultEvent(UUID eventId, DeviceId deviceId, UUID commandId,
                              String correlationId, String connectorNo, String commandType,
                              CommandResultStatus status, String failureCode, String failureReason,
                              Integer rawResultCode, Integer rawFailureCode,
                              boolean duplicate, Instant occurredAt) {
        this(eventId, deviceId, commandId, correlationId,
                "START_CHARGING".equals(commandType) ? correlationId : null,
                connectorNo, commandType, status, failureCode, failureReason,
                rawResultCode, rawFailureCode, duplicate, occurredAt);
    }

    public CommandResultEvent(UUID eventId, DeviceId deviceId, UUID commandId,
                              String correlationId, String connectorNo, String commandType,
                              CommandResultStatus status, String failureCode, String failureReason,
                              boolean duplicate, Instant occurredAt) {
        this(eventId, deviceId, commandId, correlationId,
                "START_CHARGING".equals(commandType) ? correlationId : null,
                connectorNo, commandType, status, failureCode, failureReason,
                null, null, duplicate, occurredAt);
    }
}
