package com.voltbridge.protocol.ocpp16.message;

import com.fasterxml.jackson.databind.JsonNode;

/** OCPP CALL: `[2, uniqueId, action, payload]`。 */
public record Ocpp16Call(String uniqueId, String action, JsonNode payload) implements Ocpp16Message {
}
