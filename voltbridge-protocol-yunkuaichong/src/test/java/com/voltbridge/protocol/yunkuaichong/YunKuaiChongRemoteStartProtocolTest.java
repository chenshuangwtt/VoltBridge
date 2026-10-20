package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStartEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRemoteStartResultHandler;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.transport.api.BinaryTransportPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class YunKuaiChongRemoteStartProtocolTest {

    private static final UUID COMMAND_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DEVICE_CODE = "20231212000010";
    private static final Instant NOW = Instant.parse("2026-10-18T02:00:00Z");

    @Test
    void encodesRemoteStartAndCorrelatesSuccessfulResult() {
        TimeProvider timeProvider = () -> NOW;
        YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
        YunKuaiChongPendingCommandRegistry pendingCommands =
                new YunKuaiChongPendingCommandRegistry(timeProvider, Duration.ofMinutes(10));
        YunKuaiChongRemoteStartEncoder encoder = new YunKuaiChongRemoteStartEncoder(frameCodec, pendingCommands);
        YunKuaiChongRemoteStartResultHandler resultHandler =
                new YunKuaiChongRemoteStartResultHandler(pendingCommands, timeProvider);
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(frameCodec,
                new YunKuaiChongCommandRouter(List.of(resultHandler)),
                new YunKuaiChongOutboundCommandRouter(List.of(encoder)));
        ProtocolContext context = new ProtocolContext("session-1", "/127.0.0.1:9000",
                DeviceProtocol.YUN_KUAI_CHONG_150, Optional.of(new DeviceId(DEVICE_CODE)), () -> 1);
        StartChargingCommand command = new StartChargingCommand(COMMAND_ID, new DeviceId(DEVICE_CODE),
                "01", new BigDecimal("50.00"), "5678901234567890", "5678901234567890",
                "ORDER-001", NOW);

        byte[] downlink = ((BinaryTransportPayload) adapter.encode(context, command)).value();
        YunKuaiChongFrame decoded = frameCodec.decode(downlink);

        assertThat(decoded.sequenceNumber()).isEqualTo(1);
        assertThat(decoded.command()).isEqualTo(0x34);
        assertThat(decoded.payload()).hasSize(44);
        String correlationId = BcdCodec.decode(Arrays.copyOfRange(decoded.payload(), 0, 16));
        assertThat(BcdCodec.decode(Arrays.copyOfRange(decoded.payload(), 16, 23))).isEqualTo(DEVICE_CODE);
        assertThat(BcdCodec.decode(new byte[]{decoded.payload()[23]})).isEqualTo("01");

        byte[] resultPayload = successfulResultPayload(correlationId);
        byte[] uplink = frameCodec.encode(2, 0, 0x33, resultPayload);
        ProtocolInboundResult first = adapter.decode(context, new BinaryTransportPayload(uplink));
        CommandResultEvent event = (CommandResultEvent) first.events().getFirst();

        assertThat(event.commandId()).isEqualTo(COMMAND_ID);
        assertThat(event.status()).isEqualTo(CommandResultStatus.ACKED);
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(event.duplicate()).isFalse();
        CommandResultEvent duplicate = (CommandResultEvent) adapter.decode(
                context, new BinaryTransportPayload(uplink)).events().getFirst();
        assertThat(duplicate.duplicate()).isTrue();
        System.out.println("REMOTE_START_0X34=" + HexCodec.encode(downlink));
        System.out.println("REMOTE_START_RESULT_0X33=" + HexCodec.encode(uplink));
    }

    @Test
    void mapsRejectedRemoteStartToCanonicalFailure() {
        TimeProvider timeProvider = () -> NOW;
        YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
        YunKuaiChongPendingCommandRegistry pendingCommands =
                new YunKuaiChongPendingCommandRegistry(timeProvider, Duration.ofMinutes(10));
        YunKuaiChongRemoteStartEncoder encoder = new YunKuaiChongRemoteStartEncoder(frameCodec, pendingCommands);
        YunKuaiChongRemoteStartResultHandler resultHandler =
                new YunKuaiChongRemoteStartResultHandler(pendingCommands, timeProvider);
        ProtocolContext context = new ProtocolContext("session-2", "/127.0.0.1:9000",
                DeviceProtocol.YUN_KUAI_CHONG_150, Optional.of(new DeviceId(DEVICE_CODE)), () -> 3);
        StartChargingCommand command = new StartChargingCommand(COMMAND_ID, new DeviceId(DEVICE_CODE),
                "01", new BigDecimal("50.00"), null, null, "ORDER-002", NOW);
        byte[] downlink = encoder.encode(context, command);
        String correlationId = BcdCodec.decode(Arrays.copyOfRange(frameCodec.decode(downlink).payload(), 0, 16));
        byte[] resultPayload = successfulResultPayload(correlationId);
        resultPayload[24] = 0x00;
        resultPayload[25] = 0x03;

        CommandResultEvent event = (CommandResultEvent) resultHandler
                .handle(context, frameCodec.decode(frameCodec.encode(4, 0, 0x33, resultPayload)))
                .events().getFirst();

        assertThat(event.status()).isEqualTo(CommandResultStatus.FAILED);
        assertThat(event.failureCode()).isEqualTo("DEVICE_FAULT");
        assertThat(event.failureReason()).isEqualTo("Device fault");
    }

    private byte[] successfulResultPayload(String correlationId) {
        byte[] payload = new byte[26];
        System.arraycopy(BcdCodec.encode(correlationId), 0, payload, 0, 16);
        System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 16, 7);
        payload[23] = BcdCodec.encodeByte(1);
        payload[24] = 0x01;
        payload[25] = 0x00;
        return payload;
    }
}
