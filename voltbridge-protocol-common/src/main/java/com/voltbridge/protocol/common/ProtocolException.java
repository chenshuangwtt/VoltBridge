package com.voltbridge.protocol.common;

import com.voltbridge.common.exception.VoltBridgeException;

public class ProtocolException extends VoltBridgeException {

    public ProtocolException(String code, String message) {
        super(code, message);
    }
}
