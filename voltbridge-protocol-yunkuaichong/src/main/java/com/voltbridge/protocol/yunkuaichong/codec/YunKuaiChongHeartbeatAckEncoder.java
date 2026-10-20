package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

@Component
public class YunKuaiChongHeartbeatAckEncoder {

    public static final int HEARTBEAT_ACK_COMMAND = 0x04;
    private final YunKuaiChongFrameCodec frameCodec;

    public YunKuaiChongHeartbeatAckEncoder(YunKuaiChongFrameCodec frameCodec) {
        this.frameCodec = frameCodec;
    }

    public byte[] encode(YunKuaiChongFrame request, String deviceCode, int connectorNo) {
        byte[] deviceCodeBytes = BcdCodec.encode(deviceCode);
        if (deviceCodeBytes.length != 7) {
            throw new IllegalArgumentException("YunKuaiChong device code must contain 14 BCD digits");
        }
        byte[] payload = new byte[9];
        System.arraycopy(deviceCodeBytes, 0, payload, 0, deviceCodeBytes.length);
        payload[7] = BcdCodec.encodeByte(connectorNo);
        payload[8] = 0;
        return frameCodec.encode(request.sequenceNumber(), request.encryptionFlag(),
                HEARTBEAT_ACK_COMMAND, payload);
    }
}
