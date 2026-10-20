package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

@Component
public class YunKuaiChongLoginAckEncoder {

    public static final int LOGIN_ACK_COMMAND = 0x02;

    private final YunKuaiChongFrameCodec frameCodec;

    public YunKuaiChongLoginAckEncoder(YunKuaiChongFrameCodec frameCodec) {
        this.frameCodec = frameCodec;
    }

    public byte[] encode(YunKuaiChongFrame request, String deviceCode, boolean accepted) {
        byte[] deviceCodeBytes = BcdCodec.encode(deviceCode);
        if (deviceCodeBytes.length != 7) {
            throw new IllegalArgumentException("YunKuaiChong device code must contain 14 BCD digits");
        }
        byte[] payload = new byte[8];
        System.arraycopy(deviceCodeBytes, 0, payload, 0, deviceCodeBytes.length);
        payload[7] = accepted ? (byte) 0x00 : (byte) 0x01;
        return frameCodec.encode(request.sequenceNumber(), request.encryptionFlag(), LOGIN_ACK_COMMAND, payload);
    }
}
