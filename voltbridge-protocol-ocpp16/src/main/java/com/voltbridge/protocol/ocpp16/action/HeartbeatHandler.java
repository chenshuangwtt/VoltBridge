package com.voltbridge.protocol.ocpp16.action;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 处理已认证 OCPP 会话的心跳，并刷新统一设备在线状态。 */
@Component
public class HeartbeatHandler extends Ocpp16ActionSupport implements Ocpp16ActionHandler {

    private final TimeProvider timeProvider;

    public HeartbeatHandler(Ocpp16FrameCodec codec, TimeProvider timeProvider) {
        super(codec);
        this.timeProvider = timeProvider;
    }

    @Override
    public String action() {
        return "Heartbeat";
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call) {
        DeviceId deviceId = activeDevice(context, call.uniqueId());
        Instant now = timeProvider.now();
        HeartbeatEvent event = new HeartbeatEvent(UUID.randomUUID(), deviceId, context.sessionId(), null, now);
        ObjectNode response = object().put("currentTime", now.toString());
        return eventsAndReply(List.of(event), call.uniqueId(), response);
    }
}
