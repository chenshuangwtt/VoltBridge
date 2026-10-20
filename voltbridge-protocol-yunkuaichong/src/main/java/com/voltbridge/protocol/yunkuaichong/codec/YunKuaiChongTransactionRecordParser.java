package com.voltbridge.protocol.yunkuaichong.codec;

import com.voltbridge.device.event.EnergyBreakdownItem;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.protocol.common.ProtocolException;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongTransactionRecordMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 解析云快充 V1.5 0x3B 最终交易记录。
 *
 * <p>本类是协议字节到规范数值的边界：处理 CP56Time2a、本地时区、5 字节小端电表值、
 * 尖峰平谷比例及停止码映射，但不访问数据库。</p>
 */
@Component
public class YunKuaiChongTransactionRecordParser {
    public static final int TRANSACTION_RECORD_COMMAND = 0x3B;
    private static final int PAYLOAD_LENGTH = 158;
    private final ZoneId timeZone;

    public YunKuaiChongTransactionRecordParser(
            @Value("${voltbridge.protocol.yunkuaichong.time-zone:Asia/Shanghai}") String timeZone) {
        this.timeZone = ZoneId.of(timeZone);
    }

    public YunKuaiChongTransactionRecordMessage parse(YunKuaiChongFrame frame) {
        if (frame.command() != TRANSACTION_RECORD_COMMAND) {
            throw new ProtocolException("YKC_NOT_TRANSACTION_RECORD", "Expected YunKuaiChong transaction command 0x3B");
        }
        byte[] payload = frame.payload();
        if (payload.length != PAYLOAD_LENGTH) {
            throw new ProtocolException("YKC_TRANSACTION_LENGTH", "YunKuaiChong transaction payload must contain 158 bytes");
        }
        Cursor cursor = new Cursor(payload);
        String tradeNo = BcdCodec.decode(cursor.bytes(16));
        String deviceCode = BcdCodec.decode(cursor.bytes(7));
        int connectorNo = BcdCodec.decodeByte(cursor.byteValue());
        Instant startedAt = decodeCp56(cursor.bytes(7));
        Instant endedAt = decodeCp56(cursor.bytes(7));
        // 分时明细使用可扩展集合，不把中国尖峰平谷模型固化成交易表列。
        List<EnergyBreakdownItem> breakdown = new ArrayList<>();
        StringBuilder loss = new StringBuilder("{");
        readBucket(cursor, breakdown, loss, "TOP");
        readBucket(cursor, breakdown, loss, "PEAK");
        readBucket(cursor, breakdown, loss, "FLAT");
        readBucket(cursor, breakdown, loss, "VALLEY");
        // 电表值为 5 字节无符号小端整数，直接按十进制定点数构造，避免浮点精度损失。
        BigDecimal startMeter = BigDecimal.valueOf(cursor.u40(), 4);
        BigDecimal endMeter = BigDecimal.valueOf(cursor.u40(), 4);
        BigDecimal totalEnergy = BigDecimal.valueOf(cursor.u32(), 4);
        BigDecimal lossEnergy = BigDecimal.valueOf(cursor.u32(), 4);
        BigDecimal totalAmount = BigDecimal.valueOf(cursor.u32(), 4);
        String vin = trimAscii(cursor.bytes(17));
        int rawStartMethod = cursor.u8();
        ChargingStartMethod startMethod = mapStartMethod(rawStartMethod);
        Instant transactionTime = decodeCp56(cursor.bytes(7));
        int rawStopCode = cursor.u8();
        ChargingStopCategory stopCategory = mapStopCategory(rawStopCode);
        cursor.u64();
        String additionalInfo = loss.append("\"totalLossEnergyKwh\":")
                .append(lossEnergy).append('}').toString();
        return new YunKuaiChongTransactionRecordMessage(tradeNo, deviceCode, connectorNo,
                startedAt, endedAt, startMeter, endMeter, totalEnergy, totalAmount, vin,
                startMethod, rawStartMethod, stopCategory, rawStopCode,
                stopDetail(rawStopCode), transactionTime, breakdown, additionalInfo);
    }

    private void readBucket(Cursor cursor, List<EnergyBreakdownItem> breakdown,
                            StringBuilder loss, String category) {
        BigDecimal price = BigDecimal.valueOf(cursor.u32(), 5);
        BigDecimal energy = BigDecimal.valueOf(cursor.u32(), 4);
        BigDecimal lossEnergy = BigDecimal.valueOf(cursor.u32(), 4);
        BigDecimal amount = BigDecimal.valueOf(cursor.u32(), 4);
        breakdown.add(new EnergyBreakdownItem(category, energy, amount, price));
        if (loss.length() > 1) loss.append(',');
        loss.append('\"').append(category.toLowerCase()).append("LossEnergyKwh\":").append(lossEnergy);
    }

    private Instant decodeCp56(byte[] value) {
        int millis = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
        try {
            return LocalDateTime.of(2000 + (value[6] & 0x7F), value[5] & 0x0F,
                    value[4] & 0x1F, value[3] & 0x1F, value[2] & 0x3F,
                    millis / 1000, (millis % 1000) * 1_000_000).atZone(timeZone).toInstant();
        } catch (DateTimeException exception) {
            throw new ProtocolException("YKC_CP56_TIME", "Invalid YunKuaiChong CP56Time2a value");
        }
    }

    public static ChargingStartMethod mapStartMethod(int code) {
        return switch (code) {
            case 0x01 -> ChargingStartMethod.REMOTE;
            case 0x02 -> ChargingStartMethod.CARD;
            case 0x04 -> ChargingStartMethod.OFFLINE_CARD;
            case 0x05 -> ChargingStartMethod.VIN;
            default -> ChargingStartMethod.UNKNOWN;
        };
    }

    public static ChargingStopCategory mapStopCategory(int code) {
        // 平台只保留跨协议有意义的分类，同时在事件中继续携带原始停止码。
        return switch (code) {
            case 0x40 -> ChargingStopCategory.REMOTE_STOP;
            case 0x41 -> ChargingStopCategory.TARGET_SOC_REACHED;
            case 0x42 -> ChargingStopCategory.ENERGY_LIMIT_REACHED;
            case 0x43 -> ChargingStopCategory.AMOUNT_LIMIT_REACHED;
            case 0x44 -> ChargingStopCategory.TIME_LIMIT_REACHED;
            case 0x45 -> ChargingStopCategory.USER_STOP;
            case 0x50, 0x72 -> ChargingStopCategory.EMERGENCY_STOP;
            case 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x63,
                 0x84, 0x85, 0x86, 0x87, 0x88, 0x89 -> ChargingStopCategory.COMMUNICATION_TIMEOUT;
            case 0x7B, 0x7C, 0x7D, 0x7E, 0x7F, 0x80, 0x81 -> ChargingStopCategory.BMS_FAULT;
            case 0x82 -> ChargingStopCategory.VEHICLE_STOP;
            case 0x83 -> ChargingStopCategory.POWER_FAILURE;
            case 0x4A, 0x4B, 0x4C, 0x4D, 0x4F, 0x51, 0x53, 0x54, 0x55, 0x56, 0x57,
                 0x6A, 0x6B, 0x6C, 0x6D, 0x6F, 0x70, 0x71, 0x73, 0x74, 0x75,
                 0x76, 0x77, 0x79, 0x7A -> ChargingStopCategory.DEVICE_FAULT;
            case 0x90 -> ChargingStopCategory.UNKNOWN;
            default -> ChargingStopCategory.OTHER;
        };
    }

    private static String stopDetail(int code) {
        return switch (mapStopCategory(code)) {
            case REMOTE_STOP -> "remote stop";
            case TARGET_SOC_REACHED -> "target SOC reached";
            case ENERGY_LIMIT_REACHED -> "energy limit reached";
            case AMOUNT_LIMIT_REACHED -> "amount limit reached";
            case TIME_LIMIT_REACHED -> "time limit reached";
            case USER_STOP -> "local user stop";
            case EMERGENCY_STOP -> "emergency stop";
            case COMMUNICATION_TIMEOUT -> "protocol communication timeout";
            case BMS_FAULT -> "BMS or battery fault";
            case VEHICLE_STOP -> "vehicle requested stop";
            case POWER_FAILURE -> "charger power failure";
            case DEVICE_FAULT -> "charger device fault";
            case UNKNOWN -> "unknown stop reason";
            default -> "other protocol stop reason";
        };
    }

    private String trimAscii(byte[] value) { return new String(value, StandardCharsets.US_ASCII).replace("\0", "").trim(); }

    private static final class Cursor {
        private final byte[] value; private int offset;
        private Cursor(byte[] value) { this.value = value; }
        byte[] bytes(int length) { byte[] result = Arrays.copyOfRange(value, offset, offset + length); offset += length; return result; }
        byte byteValue() { return value[offset++]; }
        int u8() { return byteValue() & 0xFF; }
        long u32() { long result = 0; for (int i = 0; i < 4; i++) result |= (long) u8() << (8 * i); return result; }
        long u40() { long result = 0; for (int i = 0; i < 5; i++) result |= (long) u8() << (8 * i); return result; }
        long u64() { long result = 0; for (int i = 0; i < 8; i++) result |= (long) u8() << (8 * i); return result; }
    }
}
