package com.voltbridge.protocol.ocpp16.message;

/** OCPP 1.6J 三种标准顶层消息。 */
public sealed interface Ocpp16Message permits Ocpp16Call, Ocpp16CallResult, Ocpp16CallError {

    String uniqueId();
}
