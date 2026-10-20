package com.voltbridge.protocol.common.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ByteCodec {

    private ByteCodec() {
    }

    public static int readUnsignedShortLittleEndian(byte[] bytes, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    public static byte[] writeShortLittleEndian(int value) {
        return ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) value).array();
    }
}
