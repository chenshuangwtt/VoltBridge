package com.voltbridge.transport.api;

import java.util.Arrays;

/** 完成边界切分后的不可变二进制载荷。 */
public record BinaryTransportPayload(byte[] value) implements TransportPayload {

    public BinaryTransportPayload {
        value = Arrays.copyOf(value, value.length);
    }

    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }
}
