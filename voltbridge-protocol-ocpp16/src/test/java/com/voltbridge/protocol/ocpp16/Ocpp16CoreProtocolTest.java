package com.voltbridge.protocol.ocpp16;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.ocpp16.action.MeterValuesHandler;
import com.voltbridge.protocol.ocpp16.action.Ocpp16ActionHandlerRegistry;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.correlation.Ocpp16PendingRequestRegistry;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallError;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallResult;
import com.voltbridge.transport.api.TextTransportPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 OCPP 数组帧、计量单位归一化和下行请求关联的协议边界。 */
class Ocpp16CoreProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Ocpp16FrameCodec codec = new Ocpp16FrameCodec(objectMapper);

    @Test
    void encodesAndDecodesStandardArrayMessagesAndRejectsMalformedFrames() {
        Ocpp16Call call = (Ocpp16Call) codec.decode("[2,\"u-1\",\"Heartbeat\",{}]");
        assertThat(call.uniqueId()).isEqualTo("u-1");
        assertThat(call.action()).isEqualTo("Heartbeat");

        assertThat(codec.decode(codec.encode(new Ocpp16CallResult("u-1", objectMapper.createObjectNode()))))
                .isInstanceOf(Ocpp16CallResult.class);
        assertThat(codec.decode(codec.encode(new Ocpp16CallError("u-2", "NotSupported", "unsupported",
                objectMapper.createObjectNode())))).isInstanceOf(Ocpp16CallError.class);
        assertThatThrownBy(() -> codec.decode("{\"type\":\"CALL\"}"))
                .isInstanceOf(Ocpp16Exception.class)
                .hasMessageContaining("JSON array");
        assertThatThrownBy(() -> codec.decode("[2,\"u-3\"]"))
                .isInstanceOf(Ocpp16Exception.class)
                .hasMessageContaining("Malformed OCPP CALL");
    }

    @Test
    void normalizesKiloUnitsAndAllowsSparseMeterValues() {
        MeterValuesHandler handler = new MeterValuesHandler(codec,
                () -> Instant.parse("2026-10-18T12:00:00Z"));
        Ocpp16Call call = (Ocpp16Call) codec.decode("""
                [2,"meter-1","MeterValues",{
                  "connectorId":1,
                  "transactionId":42,
                  "meterValue":[{"sampledValue":[
                    {"value":"2.75","measurand":"Energy.Active.Import.Register","unit":"kWh"},
                    {"value":"7.20","measurand":"Power.Active.Import","unit":"kW"},
                    {"value":"25","measurand":"Temperature","unit":"Celsius"}
                  ]}]
                }]
                """);

        var result = handler.handle(context(), call);
        ChargingProgressEvent event = (ChargingProgressEvent) result.events().getFirst();
        assertThat(event.energyKwh()).isEqualByComparingTo("2.75");
        assertThat(event.outputPowerKw()).isEqualByComparingTo("7.20");
        assertThat(event.outputVoltage()).isNull();
        assertThat(event.outputCurrent()).isNull();
        assertThat(event.additionalInfo()).contains("Temperature", "Celsius");
    }

    @Test
    void correlatesDuplicateResponsesAndExpiresPendingRequests() {
        MutableTimeProvider timeProvider = new MutableTimeProvider(Instant.parse("2026-10-18T12:00:00Z"));
        Ocpp16Properties properties = new Ocpp16Properties();
        properties.setRequestTimeout(Duration.ofSeconds(10));
        Ocpp16PendingRequestRegistry pending = new Ocpp16PendingRequestRegistry(timeProvider, properties);
        Ocpp16ProtocolAdapter adapter = new Ocpp16ProtocolAdapter(codec,
                new Ocpp16ActionHandlerRegistry(List.of()), pending, timeProvider);
        UUID commandId = UUID.randomUUID();
        TextTransportPayload request = (TextTransportPayload) adapter.encode(context(),
                new StartChargingCommand(commandId, new DeviceId("OCPP-TEST-001"), "1",
                        new BigDecimal("20"), "1234567890123456", null, "ORDER-1", timeProvider.now()));
        String uniqueId = ((Ocpp16Call) codec.decode(request.value())).uniqueId();

        TextTransportPayload accepted = new TextTransportPayload(
                "[3,\"" + uniqueId + "\",{\"status\":\"Accepted\"}]");
        CommandResultEvent first = (CommandResultEvent) adapter.decode(context(), accepted).events().getFirst();
        CommandResultEvent duplicate = (CommandResultEvent) adapter.decode(context(), accepted).events().getFirst();
        assertThat(first.commandId()).isEqualTo(commandId);
        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(adapter.decode(context(), new TextTransportPayload(
                "[3,\"unknown\",{\"status\":\"Accepted\"}]")).events()).isEmpty();

        timeProvider.now = timeProvider.now.plusSeconds(11);
        assertThat(pending.size()).isZero();
    }

    private ProtocolContext context() {
        return new ProtocolContext("session-1", "127.0.0.1", DeviceProtocol.OCPP_16,
                Optional.of(new DeviceId("OCPP-TEST-001")), () -> 1,
                Map.of("deviceCode", "OCPP-TEST-001"));
    }

    private static final class MutableTimeProvider implements TimeProvider {
        private Instant now;
        private MutableTimeProvider(Instant now) { this.now = now; }
        @Override public Instant now() { return now; }
    }
}
