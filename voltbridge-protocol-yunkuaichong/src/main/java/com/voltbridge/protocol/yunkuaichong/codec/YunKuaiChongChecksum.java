package com.voltbridge.protocol.yunkuaichong.codec;

/**
 * 云快充 V1.5 使用的 CRC16/MODBUS 校验。
 */
public final class YunKuaiChongChecksum {

    private YunKuaiChongChecksum() {
    }

    public static int crc16Modbus(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Invalid CRC input range");
        }
        int crc = 0xFFFF;
        for (int index = offset; index < offset + length; index++) {
            crc ^= data[index] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }
}
