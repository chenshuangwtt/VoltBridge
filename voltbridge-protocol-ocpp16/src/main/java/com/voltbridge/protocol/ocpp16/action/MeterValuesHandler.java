package com.voltbridge.protocol.ocpp16.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.event.DeviceEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 解析 MeterValues 的标准 measurand/unit，并输出协议无关的实时充电进度事件。
 */
@Component
public class MeterValuesHandler extends Ocpp16ActionSupport implements Ocpp16ActionHandler {

    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);
    private final TimeProvider timeProvider;

    public MeterValuesHandler(Ocpp16FrameCodec codec, TimeProvider timeProvider) {
        super(codec);
        this.timeProvider = timeProvider;
    }

    @Override
    public String action() {
        return "MeterValues";
    }

    @Override
    public ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call) {
        DeviceId deviceId = activeDevice(context, call.uniqueId());
        int connectorNo = requiredInt(call.payload(), "connectorId", call.uniqueId());
        if (connectorNo <= 0) {
            throw new Ocpp16Exception(call.uniqueId(), "PropertyConstraintViolation",
                    "connectorId must be positive");
        }
        String transactionId = call.payload().hasNonNull("transactionId")
                ? Integer.toString(requiredInt(call.payload(), "transactionId", call.uniqueId())) : null;
        JsonNode meterValues = call.payload().get("meterValue");
        if (meterValues == null || !meterValues.isArray() || meterValues.isEmpty()) {
            throw new Ocpp16Exception(call.uniqueId(), "PropertyConstraintViolation",
                    "meterValue must be a non-empty array");
        }

        List<DeviceEvent> events = new ArrayList<>();
        for (JsonNode meterValue : meterValues) {
            events.add(toProgressEvent(deviceId, connectorNo, transactionId, meterValue, call.uniqueId()));
        }
        return eventsAndReply(events, call.uniqueId(), object());
    }

    private ChargingProgressEvent toProgressEvent(DeviceId deviceId, int connectorNo,
                                                  String transactionId, JsonNode meterValue,
                                                  String uniqueId) {
        Instant now = timeProvider.now();
        Instant measuredAt = instant(meterValue, "timestamp", uniqueId, now);
        JsonNode sampledValues = meterValue.get("sampledValue");
        if (sampledValues == null || !sampledValues.isArray()) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation",
                    "sampledValue must be an array");
        }

        BigDecimal voltage = null;
        BigDecimal current = null;
        BigDecimal powerKw = null;
        BigDecimal energyKwh = null;
        Integer stateOfCharge = null;
        for (JsonNode sampledValue : sampledValues) {
            String measurand = sampledValue.path("measurand").asText("Energy.Active.Import.Register");
            String unit = sampledValue.path("unit").asText(defaultUnit(measurand));
            BigDecimal value = decimal(sampledValue, uniqueId);
            switch (measurand) {
                case "Energy.Active.Import.Register" -> energyKwh = toKilo(value, unit, "Wh", "kWh", uniqueId);
                case "Power.Active.Import" -> powerKw = toKilo(value, unit, "W", "kW", uniqueId);
                case "Voltage" -> voltage = requireUnit(value, unit, "V", uniqueId);
                case "Current.Import" -> current = requireUnit(value, unit, "A", uniqueId);
                case "SoC" -> stateOfCharge = requireUnit(value, unit, "Percent", uniqueId).intValue();
                default -> {
                    // 业务暂不消费的标准测点仍完整保留在 additionalInfo 中。
                }
            }
        }
        return new ChargingProgressEvent(UUID.randomUUID(), deviceId, DeviceProtocol.OCPP_16,
                connectorNo, transactionId, voltage, current, powerKw, stateOfCharge, energyKwh,
                null, null, json(meterValue), measuredAt);
    }

    private BigDecimal decimal(JsonNode sampledValue, String uniqueId) {
        JsonNode value = sampledValue.get("value");
        if (value == null || (!value.isTextual() && !value.isNumber())) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation", "Invalid sampled value");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation", "Invalid sampled value");
        }
    }

    private BigDecimal toKilo(BigDecimal value, String unit, String baseUnit,
                              String kiloUnit, String uniqueId) {
        if (baseUnit.equals(unit)) return value.divide(ONE_THOUSAND);
        if (kiloUnit.equals(unit)) return value;
        throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation", "Unsupported unit: " + unit);
    }

    private BigDecimal requireUnit(BigDecimal value, String actualUnit, String expectedUnit, String uniqueId) {
        if (!expectedUnit.equals(actualUnit)) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation", "Unsupported unit: " + actualUnit);
        }
        return value;
    }

    private String defaultUnit(String measurand) {
        return switch (measurand) {
            case "Power.Active.Import" -> "W";
            case "Voltage" -> "V";
            case "Current.Import" -> "A";
            case "SoC" -> "Percent";
            default -> "Wh";
        };
    }

    private String json(JsonNode value) {
        try {
            return codec.objectMapper().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot preserve OCPP meter value", exception);
        }
    }
}
