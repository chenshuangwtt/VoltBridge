package com.voltbridge.common.exception;

public class VoltBridgeException extends RuntimeException {

    private final String code;

    public VoltBridgeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
