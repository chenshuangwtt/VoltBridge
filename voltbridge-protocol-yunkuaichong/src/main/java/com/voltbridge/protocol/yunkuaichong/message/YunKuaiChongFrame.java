package com.voltbridge.protocol.yunkuaichong.message;

import java.util.Arrays;

/**
 * 已通过长度与 CRC 校验的云快充公共帧模型。
 */
public record YunKuaiChongFrame(int sequenceNumber, int encryptionFlag, int command,
                                byte[] payload, int checksum, byte[] rawFrame) {

    public YunKuaiChongFrame {
        payload = Arrays.copyOf(payload, payload.length);
        rawFrame = Arrays.copyOf(rawFrame, rawFrame.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] rawFrame() {
        return Arrays.copyOf(rawFrame, rawFrame.length);
    }
}
