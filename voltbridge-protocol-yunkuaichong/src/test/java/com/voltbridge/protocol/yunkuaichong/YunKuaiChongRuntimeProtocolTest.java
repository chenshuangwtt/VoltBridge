package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatAckEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongHeartbeatParser;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRealtimeDataParser;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongHeartbeatHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRealtimeDataHandler;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongRuntimeProtocolTest {

    private static final String DEVICE_CODE = "20231212000010";
    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
    private final YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
    private final ProtocolContext context = new ProtocolContext("session-1", "/127.0.0.1:10000",
            DeviceProtocol.YUN_KUAI_CHONG_150, Optional.of(new DeviceId(DEVICE_CODE)), () -> 1);

    @Test
    void decodesHeartbeatMapsEventAndEncodesAck() {
        byte[] payload = ByteBuffer.allocate(9)
                .put(BcdCodec.encode(DEVICE_CODE))
                .put(BcdCodec.encodeByte(1))
                .put((byte) 0)
                .array();
        YunKuaiChongFrame request = frameCodec.decode(frameCodec.encode(0x1234, 0, 0x03, payload));
        YunKuaiChongHeartbeatParser parser = new YunKuaiChongHeartbeatParser();

        assertThat(parser.parse(request).deviceCode()).isEqualTo(DEVICE_CODE);
        assertThat(parser.parse(request).connectorNo()).isEqualTo(1);

        YunKuaiChongHeartbeatHandler handler = new YunKuaiChongHeartbeatHandler(parser,
                new YunKuaiChongHeartbeatAckEncoder(frameCodec), fixedTime());
        ProtocolInboundResult result = handler.handle(context, request);

        HeartbeatEvent event = (HeartbeatEvent) result.events().getFirst();
        assertThat(event.deviceId().value()).isEqualTo(DEVICE_CODE);
        assertThat(event.sessionId()).isEqualTo("session-1");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        YunKuaiChongFrame ack = frameCodec.decode(
                ((com.voltbridge.transport.api.BinaryTransportPayload) result.immediateReplies().getFirst()).value());
        assertThat(ack.command()).isEqualTo(0x04);
        assertThat(ack.sequenceNumber()).isEqualTo(0x1234);
        assertThat(ack.payload()).containsExactly(payload);
    }

    @Test
    void decodesRealtimeStatusAndCanonicalNumericProgress() {
        String transactionId = "12345678901234567890123456789012";
        byte[] payload = realtimePayload(transactionId, 3, true, 0);
        YunKuaiChongFrame frame = frameCodec.decode(frameCodec.encode(2, 0, 0x13, payload));
        YunKuaiChongRealtimeDataHandler handler = new YunKuaiChongRealtimeDataHandler(
                new YunKuaiChongRealtimeDataParser(), fixedTime());

        ProtocolInboundResult result = handler.handle(context, frame);

        ConnectorStatusEvent status = (ConnectorStatusEvent) result.events().get(0);
        assertThat(status.connectorNo()).isEqualTo(1);
        assertThat(status.status()).isEqualTo(ConnectorStatus.CHARGING);
        ChargingProgressEvent progress = (ChargingProgressEvent) result.events().get(1);
        assertThat(progress.transactionId()).isEqualTo(transactionId);
        assertThat(progress.outputVoltage()).isEqualByComparingTo("750.5");
        assertThat(progress.outputCurrent()).isEqualByComparingTo("123.4");
        assertThat(progress.stateOfCharge()).isEqualTo(87);
        assertThat(progress.energyKwh()).isEqualByComparingTo("12.3456");
        assertThat(progress.chargingDuration()).isEqualTo(Duration.ofMinutes(125));
        assertThat(progress.chargingAmount()).isEqualByComparingTo("7.8900");
    }

    @Test
    void preservesLeadingZeroInProtocolTransactionId() {
        String transactionId = "01234567890123456789012345678901";
        YunKuaiChongFrame frame = frameCodec.decode(frameCodec.encode(
                2, 0, 0x13, realtimePayload(transactionId, 3, true, 0)));

        assertThat(new YunKuaiChongRealtimeDataParser().parse(frame).transactionId())
                .isEqualTo(transactionId);
    }

    @Test
    void mapsAvailablePluggedFaultAndUnavailableWithoutLeakingProtocolCodes() {
        YunKuaiChongRealtimeDataHandler handler = new YunKuaiChongRealtimeDataHandler(
                new YunKuaiChongRealtimeDataParser(), fixedTime());

        assertStatus(handler, realtimePayload("", 2, false, 0), ConnectorStatus.AVAILABLE);
        assertStatus(handler, realtimePayload("", 2, true, 0), ConnectorStatus.PLUGGED);
        assertStatus(handler, realtimePayload("", 1, false, 0), ConnectorStatus.FAULT);
        assertStatus(handler, realtimePayload("", 2, false, 1), ConnectorStatus.FAULT);
        assertStatus(handler, realtimePayload("", 0, false, 0), ConnectorStatus.UNAVAILABLE);
    }

    private void assertStatus(YunKuaiChongRealtimeDataHandler handler, byte[] payload,
                              ConnectorStatus expected) {
        YunKuaiChongFrame frame = frameCodec.decode(frameCodec.encode(3, 0, 0x13, payload));
        ConnectorStatusEvent event = (ConnectorStatusEvent) handler.handle(context, frame).events().getFirst();
        assertThat(event.status()).isEqualTo(expected);
    }

    private byte[] realtimePayload(String transactionId, int rawStatus, boolean plugged, int faultBits) {
        String encodedTransaction = transactionId.isBlank()
                ? "0".repeat(32) : transactionId;
        ByteBuffer payload = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(BcdCodec.encode(encodedTransaction));
        payload.put(BcdCodec.encode(DEVICE_CODE));
        payload.put(BcdCodec.encodeByte(1));
        payload.put((byte) rawStatus);
        payload.put((byte) 0);
        payload.put((byte) (plugged ? 1 : 0));
        payload.putShort((short) 7505);
        payload.putShort((short) 1234);
        payload.put((byte) 35);
        payload.putLong(123456789L);
        payload.put((byte) 87);
        payload.put((byte) 42);
        payload.putShort((short) 125);
        payload.putShort((short) 20);
        payload.putInt(123456);
        payload.putInt(123500);
        payload.putInt(78900);
        payload.putShort((short) faultBits);
        return payload.array();
    }

    private TimeProvider fixedTime() {
        return () -> NOW;
    }
}
