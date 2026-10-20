package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatParser;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongHeartbeatMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.voltbridge.transport.api.BinaryTransportPayload;
import java.util.UUID;

@Component
public class YunKuaiChongHeartbeatHandler implements YunKuaiChongCommandHandler {

    private final YunKuaiChongHeartbeatParser parser;
    private final YunKuaiChongHeartbeatAckEncoder ackEncoder;
    private final TimeProvider timeProvider;

    public YunKuaiChongHeartbeatHandler(YunKuaiChongHeartbeatParser parser,
                                        YunKuaiChongHeartbeatAckEncoder ackEncoder,
                                        TimeProvider timeProvider) {
        this.parser = parser;
        this.ackEncoder = ackEncoder;
        this.timeProvider = timeProvider;
    }

    @Override
    public int command() {
        return YunKuaiChongHeartbeatParser.HEARTBEAT_COMMAND;
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame) {
        YunKuaiChongHeartbeatMessage heartbeat = parser.parse(frame);
        DeviceId deviceId = requireBoundDevice(context, heartbeat.deviceCode());
        Instant occurredAt = timeProvider.now();
        HeartbeatEvent event = new HeartbeatEvent(UUID.randomUUID(), deviceId,
                context.sessionId(), null, occurredAt);
        byte[] ack = ackEncoder.encode(frame, heartbeat.deviceCode(), heartbeat.connectorNo());
        return new ProtocolInboundResult(List.of(event), List.of(new BinaryTransportPayload(ack)), Optional.empty());
    }

    private DeviceId requireBoundDevice(ProtocolContext context, String payloadDeviceCode) {
        DeviceId deviceId = context.deviceId().orElseThrow(() -> new ProtocolException(
                "YKC_HEARTBEAT_NOT_AUTHENTICATED", "Heartbeat received before login"));
        if (!deviceId.value().equals(payloadDeviceCode)) {
            throw new ProtocolException("YKC_HEARTBEAT_DEVICE_MISMATCH",
                    "Heartbeat device code does not match the authenticated session");
        }
        return deviceId;
    }
}
