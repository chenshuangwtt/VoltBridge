package com.voltbridge.protocol.ocpp16.message;

import com.fasterxml.jackson.databind.JsonNode;

/** OCPP CALLRESULT: `[3, uniqueId, payload]`。 */
public record Ocpp16CallResult(String uniqueId, JsonNode payload) implements Ocpp16Message {
}
