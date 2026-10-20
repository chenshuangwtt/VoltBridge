package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongLoginMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class YunKuaiChongLoginParser {

    public static final int LOGIN_COMMAND = 0x01;
    private static final int LOGIN_PAYLOAD_LENGTH = 30;

    public YunKuaiChongLoginMessage parse(YunKuaiChongFrame frame) {
        if (frame.command() != LOGIN_COMMAND) {
            throw new ProtocolException("YKC_NOT_LOGIN", "Expected YunKuaiChong login command 0x01");
        }
        byte[] payload = frame.payload();
        if (payload.length != LOGIN_PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_LOGIN_LENGTH", "YunKuaiChong login payload must contain 30 bytes");
        }
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, 0, 7));
        int pileType = payload[7] & 0xFF;
        int connectorCount = payload[8] & 0xFF;
        int protocolVersion = payload[9] & 0xFF;
        String programVersion = trimZero(new String(payload, 10, 8, StandardCharsets.US_ASCII));
        int networkType = payload[18] & 0xFF;
        String simCard = HexCodec.encode(Arrays.copyOfRange(payload, 19, 29));
        int operatorCode = payload[29] & 0xFF;
        return new YunKuaiChongLoginMessage(deviceCode, pileType, connectorCount,
                protocolVersion, programVersion, networkType, simCard, operatorCode);
    }

    private String trimZero(String value) {
        int end = value.indexOf('\0');
        return end < 0 ? value : value.substring(0, end);
    }
}
