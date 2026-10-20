package com.voltbridge.protocol.ocpp16.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolAuthenticationRequest;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.Ocpp16Properties;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallResult;
import com.voltbridge.transport.api.TextTransportPayload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 处理充电桩首次 BootNotification，并把 WebSocket 路径中的设备标识交给统一认证主线。
 *
 * <p>WebSocket 握手仅建立 TransportSession；BootNotification 经业务认证成功后才会激活
 * DeviceSession 和 DeviceRoute。CALLRESULT 使用原 CALL uniqueId，但接受决策来自业务层。</p>
 */
@Component
public class BootNotificationHandler extends Ocpp16ActionSupport implements Ocpp16ActionHandler {

    private final TimeProvider timeProvider;
    private final Ocpp16Properties properties;

    public BootNotificationHandler(Ocpp16FrameCodec codec, TimeProvider timeProvider,
                                   Ocpp16Properties properties) {
        super(codec);
        this.timeProvider = timeProvider;
        this.properties = properties;
    }

    @Override
    public String action() {
        return "BootNotification";
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call) {
        JsonNode payload = call.payload();
        requiredText(payload, "chargePointVendor", call.uniqueId());
        requiredText(payload, "chargePointModel", call.uniqueId());
        String deviceCode = context.transportAttributes().get("deviceCode");
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new Ocpp16Exception(call.uniqueId(), "SecurityError",
                    "Device identity is missing from WebSocket path");
        }

        // protocol DTO -> canonical authentication event。
        DeviceConnectedEvent event = new DeviceConnectedEvent(UUID.randomUUID(), new DeviceId(deviceCode),
                DeviceProtocol.OCPP_16, "1.6J", context.sessionId(), context.remoteAddress(), timeProvider.now());
        // 延迟构造 CALLRESULT，确保业务认证结果决定 Accepted/Rejected。
        ProtocolAuthenticationRequest request = new ProtocolAuthenticationRequest(event, accepted -> {
            ObjectNode response = object();
            response.put("status", accepted ? "Accepted" : "Rejected");
            response.put("currentTime", timeProvider.now().toString());
            response.put("interval", Math.toIntExact(properties.getHeartbeatInterval().toSeconds()));
            return new TextTransportPayload(codec.encode(new Ocpp16CallResult(call.uniqueId(), response)));
        });
        return new ProtocolInboundResult(List.of(), List.of(), Optional.of(request));
    }
}
