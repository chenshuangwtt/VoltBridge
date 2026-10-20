package com.voltbridge.protocol.ocpp16.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallResult;
import com.voltbridge.transport.api.TextTransportPayload;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** OCPP 动作处理器共享的字段校验和 CALLRESULT 构造工具。 */
abstract class Ocpp16ActionSupport {

    protected final Ocpp16FrameCodec codec;

    protected Ocpp16ActionSupport(Ocpp16FrameCodec codec) {
        this.codec = codec;
    }

    protected ProtocolInboundResult reply(String uniqueId, JsonNode payload) {
        String text = codec.encode(new Ocpp16CallResult(uniqueId, payload));
        return new ProtocolInboundResult(List.of(), List.of(new TextTransportPayload(text)), Optional.empty());
    }

    protected ProtocolInboundResult eventsAndReply(List<com.voltbridge.device.event.DeviceEvent> events,
                                                    String uniqueId, JsonNode payload) {
        String text = codec.encode(new Ocpp16CallResult(uniqueId, payload));
        return new ProtocolInboundResult(events, List.of(new TextTransportPayload(text)), Optional.empty());
    }

    protected ObjectNode object() {
        return codec.objectMapper().createObjectNode();
    }

    protected String requiredText(JsonNode payload, String field, String uniqueId) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation",
                    "Missing or invalid field: " + field);
        }
        return value.textValue();
    }

    protected int requiredInt(JsonNode payload, String field, String uniqueId) {
        JsonNode value = payload.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation",
                    "Missing or invalid field: " + field);
        }
        return value.intValue();
    }

    protected Instant instant(JsonNode payload, String field, String uniqueId, Instant fallback) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation", "Invalid timestamp: " + field);
        }
        try {
            return Instant.parse(value.textValue());
        } catch (RuntimeException exception) {
            throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation", "Invalid timestamp: " + field);
        }
    }

    protected DeviceId activeDevice(ProtocolContext context, String uniqueId) {
        return context.deviceId().orElseThrow(() ->
                new Ocpp16Exception(uniqueId, "SecurityError", "OCPP session has not completed BootNotification"));
    }
}
