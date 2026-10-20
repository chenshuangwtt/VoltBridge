package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ChargingEndedEvent;
import com.voltbridge.device.event.TransactionRecordEvent;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongEndChargeParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTransactionRecordParser;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongEndChargeHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongTransactionRecordHandler;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongTransactionProtocolTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final String TRADE_NO = "01234567890123456789012345678901";
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private final YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
    private final ProtocolContext context = new ProtocolContext("session-transaction", "/127.0.0.1:10001",
            DeviceProtocol.YUN_KUAI_CHONG_150, Optional.of(new DeviceId(DEVICE_CODE)), () -> 1);

    @Test
    void parsesEndChargeIntoCanonicalEventWithoutFinalizingProtocolSemantics() {
        ByteBuffer payload = ByteBuffer.allocate(39).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(TRADE_NO)).put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1)).put((byte) 88);
        payload.putShort((short) 321).putShort((short) 418);
        payload.put((byte) -5).put((byte) 47).putShort((short) 90).putShort((short) 100);
        payload.put(BcdCodec.encode("12345678"));
        YunKuaiChongFrame request = frameCodec.decode(frameCodec.encode(6, 0, 0x19, payload.array()));

        ProtocolInboundResult result = new YunKuaiChongEndChargeHandler(
                new YunKuaiChongEndChargeParser(), fixedTime()).handle(context, request);

        ChargingEndedEvent event = (ChargingEndedEvent) result.events().getFirst();
        assertThat(event.protocolTransactionId()).isEqualTo(TRADE_NO);
        assertThat(event.endSoc()).isEqualTo(88);
        assertThat(event.minimumCellVoltage()).isEqualByComparingTo("3.21");
        assertThat(event.maximumCellVoltage()).isEqualByComparingTo("4.18");
        assertThat(event.minimumTemperature()).isEqualTo(-5);
        assertThat(event.chargingDuration()).isEqualTo(Duration.ofMinutes(90));
        assertThat(event.outputEnergyKwh()).isEqualByComparingTo("10.0");
        assertThat(result.immediateReplies()).isEmpty();
    }

    @Test
    void preservesTransactionPrecisionMapsAbnormalStopAndEncodesCorrelatedAck() {
        YunKuaiChongFrame request = frameCodec.decode(frameCodec.encode(
                0x1234, 7, 0x3B, transactionRecordPayload(0x72)));
        YunKuaiChongTransactionRecordHandler handler = new YunKuaiChongTransactionRecordHandler(
                new YunKuaiChongTransactionRecordParser("Asia/Shanghai"),
                new YunKuaiChongTransactionRecordAckEncoder(frameCodec), fixedTime());

        ProtocolInboundResult result = handler.handle(context, request);

        TransactionRecordEvent event = (TransactionRecordEvent) result.events().getFirst();
        assertThat(event.protocolTransactionId()).isEqualTo(TRADE_NO);
        assertThat(event.startedAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 10, 15, 30, 120_000_000)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        assertThat(event.startMeterKwh()).isEqualByComparingTo("12345.6789");
        assertThat(event.endMeterKwh()).isEqualByComparingTo("12355.6789");
        assertThat(event.totalEnergyKwh()).isEqualByComparingTo("10.0000");
        assertThat(event.totalAmount()).isEqualByComparingTo("23.4567");
        assertThat(event.startMethod()).isEqualTo(ChargingStartMethod.REMOTE);
        assertThat(event.stopCategory()).isEqualTo(ChargingStopCategory.EMERGENCY_STOP);
        assertThat(event.rawStopCode()).isEqualTo(0x72);
        assertThat(event.energyBreakdown()).hasSize(4);
        assertThat(event.energyBreakdown().getFirst().unitPrice()).isEqualByComparingTo("1.23456");
        assertThat(event.energyBreakdown().getFirst().energyKwh()).isEqualByComparingTo("2.5000");
        assertThat(event.energyBreakdown().getFirst().amount()).isEqualByComparingTo("3.0864");

        YunKuaiChongFrame ack = frameCodec.decode(
                ((com.voltbridge.transport.api.BinaryTransportPayload) result.immediateReplies().getFirst()).value());
        assertThat(ack.command()).isEqualTo(0x40);
        assertThat(ack.sequenceNumber()).isEqualTo(0x1234);
        assertThat(ack.encryptionFlag()).isEqualTo(7);
        assertThat(BcdCodec.decode(Arrays.copyOfRange(ack.payload(), 0, 16))).isEqualTo(TRADE_NO);
        assertThat(ack.payload()[16]).isZero();
    }

    private byte[] transactionRecordPayload(int stopCode) {
        ByteBuffer payload = ByteBuffer.allocate(158).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(TRADE_NO)).put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1));
        payload.put(cp56(LocalDateTime.of(2026, 10, 18, 10, 15, 30, 120_000_000)));
        payload.put(cp56(LocalDateTime.of(2026, 10, 18, 11, 45, 30, 340_000_000)));
        bucket(payload, 123456, 25000, 100, 30864);
        bucket(payload, 145678, 25000, 110, 36420);
        bucket(payload, 100000, 25000, 120, 25000);
        bucket(payload, 87654, 25000, 130, 21914);
        putU40(payload, 123456789L);
        putU40(payload, 123556789L);
        payload.putInt(100000).putInt(460).putInt(234567);
        payload.put("LTESTVIN123456789".getBytes(StandardCharsets.US_ASCII));
        payload.put((byte) 0x01);
        payload.put(cp56(LocalDateTime.of(2026, 10, 18, 11, 45, 31)));
        payload.put((byte) stopCode).putLong(123456789L);
        return payload.array();
    }

    private static void bucket(ByteBuffer payload, int price, int energy, int loss, int amount) {
        payload.putInt(price).putInt(energy).putInt(loss).putInt(amount);
    }

    private static void putU40(ByteBuffer payload, long value) {
        for (int index = 0; index < 5; index++) payload.put((byte) (value >>> (index * 8)));
    }

    private static byte[] cp56(LocalDateTime value) {
        int millis = value.getSecond() * 1000 + value.getNano() / 1_000_000;
        return new byte[]{(byte) millis, (byte) (millis >>> 8), (byte) value.getMinute(),
                (byte) value.getHour(), (byte) value.getDayOfMonth(), (byte) value.getMonthValue(),
                (byte) (value.getYear() - 2000)};
    }

    private TimeProvider fixedTime() {
        return () -> NOW;
    }
}
