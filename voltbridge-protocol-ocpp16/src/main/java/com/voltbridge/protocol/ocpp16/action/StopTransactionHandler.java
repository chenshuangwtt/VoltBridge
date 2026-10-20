package com.voltbridge.protocol.ocpp16.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.TransactionRecordEvent;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 将 StopTransaction 转为统一最终交易记录；业务落库成功后网关才发送 CALLRESULT。
 *
 * <p>RemoteStopTransaction Accepted 只表示控制请求被接受；StopTransaction 才是交易结束和
 * 最终计量事实，因此可直接驱动统一交易进入 COMPLETED。</p>
 */
@Component
public class StopTransactionHandler extends Ocpp16ActionSupport implements Ocpp16ActionHandler {

    private static final BigDecimal WH_PER_KWH = BigDecimal.valueOf(1000);
    private final TimeProvider timeProvider;

    public StopTransactionHandler(Ocpp16FrameCodec codec, TimeProvider timeProvider) {
        super(codec);
        this.timeProvider = timeProvider;
    }

    @Override
    public String action() {
        return "StopTransaction";
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call) {
        DeviceId deviceId = activeDevice(context, call.uniqueId());
        int transactionId = requiredInt(call.payload(), "transactionId", call.uniqueId());
        int meterStop = requiredInt(call.payload(), "meterStop", call.uniqueId());
        if (transactionId < 0 || meterStop < 0) {
            throw new Ocpp16Exception(call.uniqueId(), "PropertyConstraintViolation",
                    "transactionId and meterStop must not be negative");
        }
        Instant now = timeProvider.now();
        Instant endedAt = instant(call.payload(), "timestamp", call.uniqueId(), now);
        String reason = call.payload().path("reason").asText("Other");
        BigDecimal endMeter = BigDecimal.valueOf(meterStop).divide(WH_PER_KWH);
        // PROTOCOL DTO -> CANONICAL EVENT：OCPP reason 保留原值，同时映射为有限平台停止分类。
        TransactionRecordEvent event = new TransactionRecordEvent(UUID.randomUUID(), deviceId,
                DeviceProtocol.OCPP_16, 0, Integer.toString(transactionId), null, endedAt,
                null, endMeter, null, null, null, null, mapReason(reason), 0, reason, endedAt,
                List.of(), json(call.payload()), transactionData(call.payload()), now);
        return eventsAndReply(List.of(event), call.uniqueId(), object());
    }

    private ChargingStopCategory mapReason(String reason) {
        return switch (reason) {
            case "Remote" -> ChargingStopCategory.REMOTE_STOP;
            case "Local", "UnlockCommand", "DeAuthorized" -> ChargingStopCategory.USER_STOP;
            case "EVDisconnected" -> ChargingStopCategory.VEHICLE_STOP;
            case "EmergencyStop" -> ChargingStopCategory.EMERGENCY_STOP;
            case "PowerLoss" -> ChargingStopCategory.POWER_FAILURE;
            case "HardReset", "SoftReset", "Reboot" -> ChargingStopCategory.DEVICE_FAULT;
            default -> ChargingStopCategory.OTHER;
        };
    }

    private String transactionData(JsonNode payload) {
        JsonNode data = payload.get("transactionData");
        return data == null ? "{}" : json(data);
    }

    private String json(JsonNode value) {
        try {
            return codec.objectMapper().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot preserve OCPP transaction payload", exception);
        }
    }
}
