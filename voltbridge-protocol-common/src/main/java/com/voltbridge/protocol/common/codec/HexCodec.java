package com.voltbridge.protocol.common.codec;

import java.util.HexFormat;

public final class HexCodec {

    private HexCodec() {
    }

    public static String encode(byte[] value) {
        return HexFormat.of().withUpperCase().formatHex(value);
    }

    public static byte[] decode(String value) {
        return HexFormat.of().parseHex(value.replace(" ", ""));
    }
}
