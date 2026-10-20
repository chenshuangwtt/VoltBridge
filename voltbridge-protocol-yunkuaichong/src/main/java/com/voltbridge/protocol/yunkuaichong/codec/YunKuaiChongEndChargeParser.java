package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongEndChargeMessage;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;

/**
 * 解析云快充 V1.5 0x19 结束充电上行负载。
 *
 * <p>负责 BCD、无符号小端数值及协议比例换算，不包含交易状态推进等业务逻辑。</p>
 */
@Component
public class YunKuaiChongEndChargeParser {
    public static final int END_CHARGE_COMMAND = 0x19;
    private static final int PAYLOAD_LENGTH = 39;

    public YunKuaiChongEndChargeMessage parse(YunKuaiChongFrame frame) {
        if (frame.command() != END_CHARGE_COMMAND) {
            throw new ProtocolException("YKC_NOT_END_CHARGE", "Expected YunKuaiChong end-charge command 0x19");
        }
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_END_CHARGE_LENGTH", "YunKuaiChong end-charge payload must contain 39 bytes");
        }
        // 字段顺序严格对应 V1.5 的 39 字节消息体，流水号保留完整 32 位以便精确关联。
        int offset = 0;
        String transactionId = BcdCodec.decode(Arrays.copyOfRange(payload, offset, offset + 16)); offset += 16;
        String deviceCode = BcdCodec.decode(Arrays.copyOfRange(payload, offset, offset + 7)); offset += 7;
        int connectorNo = BcdCodec.decodeByte(payload[offset++]);
        int endSoc = payload[offset++] & 0xFF;
        BigDecimal minVoltage = BigDecimal.valueOf(readU16(payload, offset), 2); offset += 2;
        BigDecimal maxVoltage = BigDecimal.valueOf(readU16(payload, offset), 2); offset += 2;
        int minTemperature = payload[offset++];
        int maxTemperature = payload[offset++];
        Duration duration = Duration.ofMinutes(readU16(payload, offset)); offset += 2;
        BigDecimal outputEnergy = BigDecimal.valueOf(readU16(payload, offset), 1); offset += 2;
        String chargerCode = BcdCodec.decode(Arrays.copyOfRange(payload, offset, offset + 4));
        return new YunKuaiChongEndChargeMessage(transactionId, deviceCode, connectorNo, endSoc,
                minVoltage, maxVoltage, minTemperature, maxTemperature, duration, outputEnergy, chargerCode);
    }

    private int readU16(byte[] value, int offset) {
        return (value[offset] & 0xFF) | ((value[offset + 1] & 0xFF) << 8);
    }

}
