package com.voltbridge.protocol.ocpp16.message;

import com.fasterxml.jackson.databind.JsonNode;

/** OCPP CALLERROR: `[4, uniqueId, errorCode, errorDescription, errorDetails]`。 */
public record Ocpp16CallError(String uniqueId, String errorCode, String errorDescription,
                              JsonNode errorDetails) implements Ocpp16Message {
}
