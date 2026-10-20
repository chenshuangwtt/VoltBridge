package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongRealtimeDataMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;

/**
 * 解析云快充 V1.5 0x13 实时状态和充电进度数据。
 */
@Component
public class YunKuaiChongRealtimeDataParser {

    public static final int REALTIME_DATA_COMMAND = 0x13;
    private static final int PAYLOAD_LENGTH = 60;

    public YunKuaiChongRealtimeDataMessage parse(YunKuaiChongFrame frame) {
        if (frame.command() != REALTIME_DATA_COMMAND) {
            throw new ProtocolException("YKC_NOT_REALTIME_DATA",
                    "Expected YunKuaiChong realtime data command 0x13");
        }
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_REALTIME_DATA_LENGTH",
                    "YunKuaiChong realtime data payload must contain 60 bytes");
        }
        int offset = 0;
        String transactionId = normalizeTransactionId(
                BcdCodec.decode(Arrays.copyOfRange(payload, offset, offset + 16)));
        offset += 16;
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, offset, offset + 7));
        offset += 7;
        int connectorNo = BcdCodec.decodeByte(payload[offset++]);
        int rawStatus = payload[offset++] & 0xFF;
        int homingStatus = payload[offset++] & 0xFF;
        boolean plugged = (payload[offset++] & 0xFF) == 1;
        BigDecimal outputVoltage = BigDecimal.valueOf(readUnsignedShortLe(payload, offset), 1);
        offset += 2;
        BigDecimal outputCurrent = BigDecimal.valueOf(readUnsignedShortLe(payload, offset), 1);
        offset += 2;
        offset += 1;
        offset += 8;
        int stateOfCharge = payload[offset++] & 0xFF;
        offset += 1;
        Duration chargingDuration = Duration.ofMinutes(readUnsignedShortLe(payload, offset));
        offset += 2;
        offset += 2;
        BigDecimal energyKwh = BigDecimal.valueOf(readUnsignedIntLe(payload, offset), 4);
        offset += 4;
        offset += 4;
        BigDecimal chargingAmount = BigDecimal.valueOf(readUnsignedIntLe(payload, offset), 4);
        offset += 4;
        int faultBits = readUnsignedShortLe(payload, offset);
        return new YunKuaiChongRealtimeDataMessage(transactionId, deviceCode, connectorNo,
                rawStatus, homingStatus, plugged, outputVoltage, outputCurrent, stateOfCharge,
                chargingDuration, energyKwh, chargingAmount, faultBits);
    }

    private int readUnsignedShortLe(byte[] payload, int offset) {
        return (payload[offset] & 0xFF) | ((payload[offset + 1] & 0xFF) << 8);
    }

    private long readUnsignedIntLe(byte[] payload, int offset) {
        return Integer.toUnsignedLong((payload[offset] & 0xFF)
                | ((payload[offset + 1] & 0xFF) << 8)
                | ((payload[offset + 2] & 0xFF) << 16)
                | ((payload[offset + 3] & 0xFF) << 24));
    }

    private String normalizeTransactionId(String value) {
        // 全零表示设备当前无交易；有效 32 位流水必须保留前导零，确保与 0x33/0x3B 一致。
        return value.chars().allMatch(character -> character == '0') ? "" : value;
    }
}
