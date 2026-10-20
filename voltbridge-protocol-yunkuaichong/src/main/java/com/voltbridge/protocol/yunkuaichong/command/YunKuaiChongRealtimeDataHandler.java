package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRealtimeDataParser;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongRealtimeDataMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class YunKuaiChongRealtimeDataHandler implements YunKuaiChongCommandHandler {

    private final YunKuaiChongRealtimeDataParser parser;
    private final TimeProvider timeProvider;

    public YunKuaiChongRealtimeDataHandler(YunKuaiChongRealtimeDataParser parser,
                                           TimeProvider timeProvider) {
        this.parser = parser;
        this.timeProvider = timeProvider;
    }

    @Override
    public int command() {
        return YunKuaiChongRealtimeDataParser.REALTIME_DATA_COMMAND;
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        YunKuaiChongRealtimeDataMessage realtime = parser.parse(frame);
        DeviceId deviceId = requireBoundDevice(context, realtime.deviceCode());
        Instant occurredAt = timeProvider.now();
        ConnectorStatus status = mapStatus(realtime);
        List<DeviceEvent> events = new ArrayList<>();
        events.add(new ConnectorStatusEvent(UUID.randomUUID(), deviceId,
                realtime.connectorNo(), status, occurredAt));
        if (!realtime.transactionId().isBlank()) {
            events.add(new ChargingProgressEvent(UUID.randomUUID(), deviceId, realtime.connectorNo(),
                    realtime.transactionId(), realtime.outputVoltage(), realtime.outputCurrent(),
                    realtime.stateOfCharge(), realtime.energyKwh(), realtime.chargingDuration(),
                    realtime.chargingAmount(), occurredAt));
        }
        return ProtocolInboundResult.events(events);
    }

    ConnectorStatus mapStatus(YunKuaiChongRealtimeDataMessage realtime) {
        if (realtime.faultBits() != 0 || realtime.rawStatus() == 1) {
            return ConnectorStatus.FAULT;
        }
        return switch (realtime.rawStatus()) {
            case 0 -> ConnectorStatus.UNAVAILABLE;
            case 2 -> realtime.plugged() ? ConnectorStatus.PLUGGED : ConnectorStatus.AVAILABLE;
            case 3 -> realtime.transactionId().isBlank()
                    ? ConnectorStatus.PLUGGED : ConnectorStatus.CHARGING;
            default -> ConnectorStatus.UNKNOWN;
        };
    }

    private DeviceId requireBoundDevice(ProtocolContext context, String payloadDeviceCode) {
        DeviceId deviceId = context.deviceId().orElseThrow(() -> new ProtocolException(
                "YKC_REALTIME_NOT_AUTHENTICATED", "Realtime data received before login"));
        if (!deviceId.value().equals(payloadDeviceCode)) {
            throw new ProtocolException("YKC_REALTIME_DEVICE_MISMATCH",
                    "Realtime data device code does not match the authenticated session");
        }
        return deviceId;
    }
}
