package com.voltbridge.device.event;

import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备侧事实转换后的统一领域事件。
 *
 * <p>协议适配器负责产生事件，业务应用通过 {@code DeviceEventPublisher} 消费事件，避免依赖协议报文细节。</p>
 */
public sealed interface DeviceEvent permits DeviceConnectedEvent, DeviceDisconnectedEvent, HeartbeatEvent,
        ConnectorStatusEvent, ChargingProgressEvent, ChargingEndedEvent, TransactionRecordEvent,
        CommandResultEvent, DeviceDiagnosticEvent {

    UUID eventId();

    DeviceId deviceId();

    Instant occurredAt();
}
