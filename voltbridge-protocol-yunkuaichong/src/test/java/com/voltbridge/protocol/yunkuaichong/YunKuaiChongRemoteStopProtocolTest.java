package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.ConnectorId;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.common.codec.HexCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongRemoteStopEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongRemoteStopResultHandler;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;
import com.voltbridge.transport.api.BinaryTransportPayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 校准云快充远程停充字段、关联方式、失败映射和重复结果语义。 */
class YunKuaiChongRemoteStopProtocolTest {

    private static final UUID COMMAND_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID TRANSACTION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DEVICE_CODE = "20231212000010";
    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");

    @Test
    void encodesRemoteStopAndCorrelatesDuplicateSuccessfulResult() {
        Fixture fixture = fixture();
        byte[] downlink = ((BinaryTransportPayload) fixture.adapter.encode(fixture.context, command())).value();
        YunKuaiChongFrame decoded = fixture.frameCodec.decode(downlink);

        assertThat(decoded.sequenceNumber()).isEqualTo(7);
        assertThat(decoded.command()).isEqualTo(0x36);
        assertThat(decoded.payload()).hasSize(8);
        assertThat(BcdCodec.decode(Arrays.copyOfRange(decoded.payload(), 0, 7))).isEqualTo(DEVICE_CODE);
        assertThat(BcdCodec.decode(new byte[]{decoded.payload()[7]})).isEqualTo("01");

        byte[] uplink = fixture.frameCodec.encode(8, 0, 0x35, resultPayload(0x01, 0x00));
        CommandResultEvent first = (CommandResultEvent) fixture.adapter.decode(
                        fixture.context, new BinaryTransportPayload(uplink))
                .events().getFirst();
        CommandResultEvent duplicate = (CommandResultEvent) fixture.adapter.decode(
                        fixture.context, new BinaryTransportPayload(uplink))
                .events().getFirst();

        assertThat(first.commandId()).isEqualTo(COMMAND_ID);
        assertThat(first.status()).isEqualTo(CommandResultStatus.ACKED);
        assertThat(first.rawResultCode()).isEqualTo(1);
        assertThat(first.rawFailureCode()).isZero();
        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        System.out.println("REMOTE_STOP_0X36=" + HexCodec.encode(downlink));
        System.out.println("REMOTE_STOP_RESULT_0X35=" + HexCodec.encode(uplink));
    }

    @Test
    void mapsRejectedRemoteStopAndPreservesRawCodes() {
        Fixture fixture = fixture();
        fixture.adapter.encode(fixture.context, command());
        byte[] uplink = fixture.frameCodec.encode(9, 0, 0x35, resultPayload(0x00, 0x02));

        CommandResultEvent event = (CommandResultEvent) fixture.adapter.decode(
                        fixture.context, new BinaryTransportPayload(uplink))
                .events().getFirst();

        assertThat(event.status()).isEqualTo(CommandResultStatus.FAILED);
        assertThat(event.failureCode()).isEqualTo("NOT_CHARGING");
        assertThat(event.rawResultCode()).isZero();
        assertThat(event.rawFailureCode()).isEqualTo(2);
    }

    private Fixture fixture() {
        TimeProvider timeProvider = () -> NOW;
        YunKuaiChongFrameCodec frameCodec = new YunKuaiChongFrameCodec();
        YunKuaiChongPendingCommandRegistry pending =
                new YunKuaiChongPendingCommandRegistry(timeProvider, Duration.ofMinutes(10));
        YunKuaiChongRemoteStopEncoder encoder = new YunKuaiChongRemoteStopEncoder(frameCodec, pending);
        YunKuaiChongRemoteStopResultHandler handler =
                new YunKuaiChongRemoteStopResultHandler(pending, timeProvider);
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(frameCodec,
                new YunKuaiChongCommandRouter(List.of(handler)),
                new YunKuaiChongOutboundCommandRouter(List.of(encoder)));
        ProtocolContext context = new ProtocolContext("session-stop", "/127.0.0.1:9000",
                DeviceProtocol.YUN_KUAI_CHONG_150, Optional.of(new DeviceId(DEVICE_CODE)), () -> 7);
        return new Fixture(frameCodec, adapter, context);
    }

    private StopChargingCommand command() {
        return new StopChargingCommand(COMMAND_ID, new DeviceId(DEVICE_CODE),
                new ConnectorId(UUID.randomUUID().toString()), "01", TRANSACTION_ID,
                "12345678901234567890123456789012", NOW);
    }

    private byte[] resultPayload(int result, int failure) {
        byte[] payload = new byte[10];
        System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 0, 7);
        payload[7] = BcdCodec.encodeByte(1);
        payload[8] = (byte) result;
        payload[9] = (byte) failure;
        return payload;
    }

    private record Fixture(YunKuaiChongFrameCodec frameCodec,
                           YunKuaiChongProtocolAdapter adapter,
                           ProtocolContext context) {
    }
}
