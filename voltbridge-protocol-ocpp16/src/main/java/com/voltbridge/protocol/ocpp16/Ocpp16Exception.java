package com.voltbridge.protocol.ocpp16;

/** 可转换为 OCPP CALLERROR 的协议校验异常。 */
public class Ocpp16Exception extends RuntimeException {

    private final String uniqueId;
    private final String errorCode;

    public Ocpp16Exception(String uniqueId, String errorCode, String message) {
        super(message);
        this.uniqueId = uniqueId;
        this.errorCode = errorCode;
    }

    public String uniqueId() { return uniqueId; }
    public String errorCode() { return errorCode; }
}
