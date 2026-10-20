package com.voltbridge.protocol.yunkuaichong;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.ResetDeviceCommand;
import com.voltbridge.device.command.SynchronizeTimeCommand;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.event.DeviceDiagnosticEvent;
import com.voltbridge.device.model.DeviceTransactionStartResult;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.codec.BcdCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongFrameCodec;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongResetDeviceEncoder;
import com.voltbridge.protocol.yunkuaichong.codec.YunKuaiChongTimeSyncEncoder;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongBmsChargingErrorHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongOutboundCommandRouter;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongPendingCommandRegistry;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongResetResultHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongTimeSyncResultHandler;
import com.voltbridge.protocol.yunkuaichong.command.YunKuaiChongStartChargeHandler;
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

/** 校准对时、重启和 BMS 诊断帧布局及规范事件映射。 */
class YunKuaiChongOperationsProtocolTest {
    private static final String DEVICE_CODE = "20231212000010";
    private static final Instant NOW = Instant.parse("2026-10-19T08:09:10Z");

    @Test
    void encodesTimeSyncAndCompletesOnResult() {
        Fixture fixture = fixture();
        UUID commandId = UUID.randomUUID();
        byte[] downlink = ((BinaryTransportPayload) fixture.adapter.encode(fixture.context,
                new SynchronizeTimeCommand(commandId, new DeviceId(DEVICE_CODE), NOW, NOW))).value();
        YunKuaiChongFrame decoded = fixture.codec.decode(downlink);
        assertThat(decoded.command()).isEqualTo(0x56);
        assertThat(decoded.payload()).hasSize(14);
        assertThat(BcdCodec.decode(Arrays.copyOfRange(decoded.payload(), 0, 7))).isEqualTo(DEVICE_CODE);

        byte[] result = fixture.codec.encode(8, 0, 0x55, decoded.payload());
        CommandResultEvent event = (CommandResultEvent) fixture.adapter.decode(fixture.context,
                new BinaryTransportPayload(result)).events().getFirst();
        assertThat(event.commandId()).isEqualTo(commandId);
        assertThat(event.status()).isEqualTo(CommandResultStatus.SUCCEEDED);
    }

    @Test
    void encodesResetAndUsesResultAsAuthoritativeCompletion() {
        Fixture fixture = fixture();
        UUID commandId = UUID.randomUUID();
        byte[] downlink = ((BinaryTransportPayload) fixture.adapter.encode(fixture.context,
                new ResetDeviceCommand(commandId, new DeviceId(DEVICE_CODE), true, NOW))).value();
        YunKuaiChongFrame decoded = fixture.codec.decode(downlink);
        assertThat(decoded.command()).isEqualTo(0x92);
        assertThat(decoded.payload()).hasSize(11);
        assertThat(decoded.payload()[10]).isEqualTo((byte) 2);

        byte[] payload = new byte[8];
        System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 0, 7);
        payload[7] = 1;
        CommandResultEvent event = (CommandResultEvent) fixture.adapter.decode(fixture.context,
                new BinaryTransportPayload(fixture.codec.encode(9, 0, 0x91, payload))).events().getFirst();
        assertThat(event.commandId()).isEqualTo(commandId);
        assertThat(event.status()).isEqualTo(CommandResultStatus.SUCCEEDED);
    }

    @Test
    void mapsBmsChargingErrorToDiagnosticEventWithoutRawBytes() {
        Fixture fixture = fixture();
        byte[] payload = new byte[32];
        System.arraycopy(BcdCodec.encode("12345678901234567890123456789012"), 0, payload, 0, 16);
        System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 16, 7);
        payload[23] = BcdCodec.encodeByte(1);
        payload[24] = 0x05;
        DeviceDiagnosticEvent event = (DeviceDiagnosticEvent) fixture.adapter.decode(fixture.context,
                new BinaryTransportPayload(fixture.codec.encode(10, 0, 0x1B, payload))).events().getFirst();
        assertThat(event.category()).isEqualTo("BMS_COMMUNICATION");
        assertThat(event.connectorNo()).isEqualTo("01");
        assertThat(event.protocolDetail()).contains("TIMEOUT");
    }

    @Test
    void parsesDeviceInitiatedStartAndEncodesBusinessDecisionReply() {
        TimeProvider time = () -> NOW;
        YunKuaiChongFrameCodec codec = new YunKuaiChongFrameCodec();
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(codec,
                new YunKuaiChongCommandRouter(List.of(new YunKuaiChongStartChargeHandler(codec, time))),
                new YunKuaiChongOutboundCommandRouter(List.of()));
        byte[] payload = new byte[51];
        System.arraycopy(BcdCodec.encode(DEVICE_CODE), 0, payload, 0, 7);
        payload[7] = BcdCodec.encodeByte(1);
        payload[8] = 1;
        byte[] request = codec.encode(23, 0, 0x31, payload);

        var result = adapter.decode(context(), new BinaryTransportPayload(request));
        assertThat(result.events()).isEmpty();
        assertThat(result.transactionStartRequest()).isPresent();
        assertThat(result.transactionStartRequest().orElseThrow().request().connectorNo()).isEqualTo(1);
        assertThat(result.transactionStartRequest().orElseThrow().request().startMethod().name()).isEqualTo("CARD");

        byte[] reply = ((BinaryTransportPayload) result.transactionStartRequest().orElseThrow().responder()
                .encode(new DeviceTransactionStartResult(true, 42L, UUID.randomUUID(), "ACCEPTED"))).value();
        YunKuaiChongFrame acknowledgement = codec.decode(reply);
        assertThat(acknowledgement.command()).isEqualTo(0x32);
        assertThat(acknowledgement.sequenceNumber()).isEqualTo(23);
        assertThat(acknowledgement.payload()).hasSize(44);
        assertThat(BcdCodec.decode(Arrays.copyOfRange(acknowledgement.payload(), 0, 16))).isEqualTo("00000000000000000000000000000042");
    }

    private Fixture fixture() {
        TimeProvider time = () -> NOW;
        YunKuaiChongFrameCodec codec = new YunKuaiChongFrameCodec();
        YunKuaiChongPendingCommandRegistry pending = new YunKuaiChongPendingCommandRegistry(time, Duration.ofMinutes(10));
        YunKuaiChongProtocolAdapter adapter = new YunKuaiChongProtocolAdapter(codec,
                new YunKuaiChongCommandRouter(List.of(new YunKuaiChongTimeSyncResultHandler(pending, time),
                        new YunKuaiChongResetResultHandler(pending, time), new YunKuaiChongBmsChargingErrorHandler(time))),
                new YunKuaiChongOutboundCommandRouter(List.of(new YunKuaiChongTimeSyncEncoder(codec, pending, "Asia/Shanghai"),
                        new YunKuaiChongResetDeviceEncoder(codec, pending))));
        return new Fixture(codec, adapter, context());
    }

    private ProtocolContext context() {
        return new ProtocolContext("operations", "/127.0.0.1:9000", DeviceProtocol.YUN_KUAI_CHONG_150,
                Optional.of(new DeviceId(DEVICE_CODE)), () -> 7);
    }

    private record Fixture(YunKuaiChongFrameCodec codec, YunKuaiChongProtocolAdapter adapter, ProtocolContext context) { }
}
