package com.voltbridge.protocol.common.codec;

import java.util.Objects;

public final class BcdCodec {

    private BcdCodec() {
    }

    public static int decodeByte(byte value) {
        int high = (value >>> 4) & 0x0F;
        int low = value & 0x0F;
        if (high > 9 || low > 9) {
            throw new IllegalArgumentException("Invalid BCD byte");
        }
        return high * 10 + low;
    }

    public static byte encodeByte(int value) {
        if (value < 0 || value > 99) {
            throw new IllegalArgumentException("BCD value must be between 0 and 99");
        }
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    public static String decode(byte[] value) {
        Objects.requireNonNull(value, "BCD value must not be null");
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            int decoded = decodeByte(item);
            if (decoded < 10) {
                result.append('0');
            }
            result.append(decoded);
        }
        return result.toString();
    }

    public static byte[] encode(String value) {
        if (value == null || value.isBlank() || (value.length() & 1) != 0) {
            throw new IllegalArgumentException("BCD text must contain an even number of digits");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 10);
            int low = Character.digit(value.charAt(index * 2 + 1), 10);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("BCD text must contain digits only");
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
