package com.voltbridge.protocol.ocpp16.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.voltbridge.protocol.ocpp16.Ocpp16Exception;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallError;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallResult;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Message;
import org.springframework.stereotype.Component;

/** 集中验证和编解码 OCPP 1.6J 顶层 JSON 数组。 */
@Component
public class Ocpp16FrameCodec {

    private final ObjectMapper objectMapper;

    public Ocpp16FrameCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Ocpp16Message decode(String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            if (!(node instanceof ArrayNode array) || array.isEmpty() || !array.get(0).canConvertToInt()) {
                throw new Ocpp16Exception("unknown", "FormationViolation", "OCPP message must be a JSON array");
            }
            return switch (array.get(0).intValue()) {
                case 2 -> decodeCall(array);
                case 3 -> decodeResult(array);
                case 4 -> decodeError(array);
                default -> throw new Ocpp16Exception(uniqueId(array), "FormationViolation",
                        "Unsupported OCPP message type");
            };
        } catch (JsonProcessingException exception) {
            throw new Ocpp16Exception("unknown", "FormationViolation", "Malformed JSON");
        }
    }

    public String encode(Ocpp16Message message) {
        ArrayNode array = objectMapper.createArrayNode();
        if (message instanceof Ocpp16Call call) {
            array.add(2).add(call.uniqueId()).add(call.action()).add(call.payload());
        } else if (message instanceof Ocpp16CallResult result) {
            array.add(3).add(result.uniqueId()).add(result.payload());
        } else if (message instanceof Ocpp16CallError error) {
            array.add(4).add(error.uniqueId()).add(error.errorCode())
                    .add(error.errorDescription()).add(error.errorDetails());
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode OCPP message", exception);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    private Ocpp16Call decodeCall(ArrayNode array) {
        if (array.size() != 4 || !array.get(1).isTextual() || !array.get(2).isTextual()
                || !array.get(3).isObject()) {
            throw new Ocpp16Exception(uniqueId(array), "FormationViolation", "Malformed OCPP CALL");
        }
        return new Ocpp16Call(array.get(1).textValue(), array.get(2).textValue(), array.get(3));
    }

    private Ocpp16CallResult decodeResult(ArrayNode array) {
        if (array.size() != 3 || !array.get(1).isTextual() || !array.get(2).isObject()) {
            throw new Ocpp16Exception(uniqueId(array), "FormationViolation", "Malformed OCPP CALLRESULT");
        }
        return new Ocpp16CallResult(array.get(1).textValue(), array.get(2));
    }

    private Ocpp16CallError decodeError(ArrayNode array) {
        if (array.size() != 5 || !array.get(1).isTextual() || !array.get(2).isTextual()
                || !array.get(3).isTextual() || !array.get(4).isObject()) {
            throw new Ocpp16Exception(uniqueId(array), "FormationViolation", "Malformed OCPP CALLERROR");
        }
        return new Ocpp16CallError(array.get(1).textValue(), array.get(2).textValue(),
                array.get(3).textValue(), array.get(4));
    }

    private String uniqueId(ArrayNode array) {
        return array.size() > 1 && array.get(1).isTextual() ? array.get(1).textValue() : "unknown";
    }
}
