package com.voltbridge.protocol.ocpp16.action;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 将 OCPP StatusNotification 映射为协议无关枪口状态事件。 */
@Component
public class StatusNotificationHandler extends Ocpp16ActionSupport implements Ocpp16ActionHandler {

    private final TimeProvider timeProvider;

    public StatusNotificationHandler(Ocpp16FrameCodec codec, TimeProvider timeProvider) {
        super(codec);
        this.timeProvider = timeProvider;
    }

    @Override
    public String action() {
        return "StatusNotification";
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call) {
        DeviceId deviceId = activeDevice(context, call.uniqueId());
        int connectorNo = requiredInt(call.payload(), "connectorId", call.uniqueId());
        if (connectorNo < 0) {
            throw new Ocpp16Exception(call.uniqueId(), "PropertyConstraintViolation",
                    "connectorId must not be negative");
        }
        String status = requiredText(call.payload(), "status", call.uniqueId());
        if (connectorNo == 0) {
            return reply(call.uniqueId(), object());
        }
        ConnectorStatusEvent event = new ConnectorStatusEvent(UUID.randomUUID(), deviceId, connectorNo,
                mapStatus(status), timeProvider.now());
        return eventsAndReply(List.of(event), call.uniqueId(), object());
    }

    private ConnectorStatus mapStatus(String status) {
        return switch (status) {
            case "Available" -> ConnectorStatus.AVAILABLE;
            case "Preparing" -> ConnectorStatus.PLUGGED;
            case "Charging", "SuspendedEV", "SuspendedEVSE" -> ConnectorStatus.CHARGING;
            case "Finishing" -> ConnectorStatus.CHARGE_COMPLETE;
            case "Reserved" -> ConnectorStatus.RESERVED;
            case "Unavailable" -> ConnectorStatus.UNAVAILABLE;
            case "Faulted" -> ConnectorStatus.FAULT;
            default -> ConnectorStatus.UNKNOWN;
        };
    }
}
