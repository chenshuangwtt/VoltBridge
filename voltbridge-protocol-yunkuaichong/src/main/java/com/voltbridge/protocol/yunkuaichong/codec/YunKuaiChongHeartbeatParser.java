package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongHeartbeatMessage;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class YunKuaiChongHeartbeatParser {

    public static final int HEARTBEAT_COMMAND = 0x03;
    private static final int PAYLOAD_LENGTH = 9;

    public YunKuaiChongHeartbeatMessage parse(YunKuaiChongFrame frame) {
        if (frame.command() != HEARTBEAT_COMMAND) {
            throw new ProtocolException("YKC_NOT_HEARTBEAT", "Expected YunKuaiChong heartbeat command 0x03");
        }
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_HEARTBEAT_LENGTH",
                    "YunKuaiChong heartbeat payload must contain 9 bytes");
        }
        return new YunKuaiChongHeartbeatMessage(
                BcdCodec.decode(Arrays.copyOfRange(payload, 0, 7)),
                BcdCodec.decodeByte(payload[7]), payload[8] & 0xFF);
    }
}
