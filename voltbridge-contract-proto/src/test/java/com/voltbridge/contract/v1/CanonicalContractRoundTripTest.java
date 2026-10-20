package com.voltbridge.contract.v1;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.device.command.ResetDeviceCommand;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.command.SynchronizeTimeCommand;
import com.voltbridge.device.event.ChargingProgressEvent;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.event.DeviceDiagnosticEvent;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.ConnectorId;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证标准设备命令和事件经过 Protobuf 网络契约后仍保持身份、关联和业务字段。
 *
 * <p>主线为“标准 Java 模型 -> Envelope -> bytes -> Envelope -> 标准 Java 模型”，
 * 不启动 gRPC，也不让生成类进入业务模块。</p>
 */
class CanonicalContractRoundTripTest {

    private static final DeviceId DEVICE_ID = new DeviceId("20231212000010");
    private static final Instant NOW = Instant.parse("2026-10-18T10:15:30Z");

    @Test
    void roundTripsDeviceConnectedEvent() throws Exception {
        DeviceConnectedEvent source = new DeviceConnectedEvent(UUID.randomUUID(), DEVICE_ID,
                DeviceProtocol.OCPP_16, "1.6J", "session-1", "/127.0.0.1:9100", NOW);

        DeviceConnectedEvent restored = connected(DeviceEventEnvelope.parseFrom(connected(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void roundTripsHeartbeatEvent() throws Exception {
        HeartbeatEvent source = new HeartbeatEvent(UUID.randomUUID(), DEVICE_ID, "session-2",
                NOW.minusSeconds(2), NOW);

        HeartbeatEvent restored = heartbeat(DeviceEventEnvelope.parseFrom(heartbeat(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void roundTripsConnectorStatusEvent() throws Exception {
        ConnectorStatusEvent source = new ConnectorStatusEvent(UUID.randomUUID(), DEVICE_ID, 1,
                ConnectorStatus.CHARGING, NOW);

        ConnectorStatusEvent restored = connectorStatus(
                DeviceEventEnvelope.parseFrom(connectorStatus(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void roundTripsChargingProgressEventWithoutLosingDecimalPrecision() throws Exception {
        ChargingProgressEvent source = new ChargingProgressEvent(UUID.randomUUID(), DEVICE_ID,
                DeviceProtocol.YUN_KUAI_CHONG_150, 1, "2400000000000001",
                new BigDecimal("387.50"), new BigDecimal("52.125"), new BigDecimal("20.197500"),
                68, new BigDecimal("12.345678"), Duration.ofMinutes(37),
                new BigDecimal("18.765432"), "{\"source\":\"meter\"}", NOW);

        ChargingProgressEvent restored = chargingProgress(
                DeviceEventEnvelope.parseFrom(chargingProgress(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void roundTripsCommandResultEventCorrelations() throws Exception {
        CommandResultEvent source = new CommandResultEvent(UUID.randomUUID(), DEVICE_ID, UUID.randomUUID(),
                "wire-123", "tx-456", "01", "START_CHARGING", CommandResultStatus.FAILED,
                "DEVICE_REJECTED", "Rejected by device", 0, 3, false, NOW);

        CommandResultEvent restored = commandResult(
                DeviceEventEnvelope.parseFrom(commandResult(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void roundTripsStartChargingCommand() throws Exception {
        StartChargingCommand source = new StartChargingCommand(UUID.randomUUID(), DEVICE_ID, "01",
                new BigDecimal("50.00"), "logical-1", "physical-1", "ORDER-100", NOW);

        StartChargingCommand restored = startCommand(
                DeviceCommandEnvelope.parseFrom(startCommand(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void roundTripsStopChargingCommand() throws Exception {
        StopChargingCommand source = new StopChargingCommand(UUID.randomUUID(), DEVICE_ID,
                new ConnectorId(UUID.randomUUID().toString()), "01", UUID.randomUUID(),
                "2400000000000001", NOW);

        StopChargingCommand restored = stopCommand(
                DeviceCommandEnvelope.parseFrom(stopCommand(source).toByteArray()));

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void mapsTimeSyncAndResetThroughCanonicalProtoMapper() {
        SynchronizeTimeCommand sync = new SynchronizeTimeCommand(UUID.randomUUID(), DEVICE_ID, NOW.plusSeconds(60), NOW);
        ResetDeviceCommand reset = new ResetDeviceCommand(UUID.randomUUID(), DEVICE_ID, true, NOW);

        assertThat(DeviceProtoMapper.fromProto(DeviceProtoMapper.toProto(sync))).isEqualTo(sync);
        assertThat(DeviceProtoMapper.fromProto(DeviceProtoMapper.toProto(reset))).isEqualTo(reset);
    }

    @Test
    void mapsDiagnosticEventThroughCanonicalProtoMapper() {
        DeviceDiagnosticEvent source = new DeviceDiagnosticEvent(UUID.randomUUID(), DEVICE_ID, "01", "ERROR",
                "BMS_COMMUNICATION", "YKC_BMS_CHARGING_ERROR", "BMS timeout", "TX-1",
                "{\"bmsStatus\":\"TIMEOUT\"}", NOW);
        assertThat(DeviceProtoMapper.fromProto(DeviceProtoMapper.toProto(source))).isEqualTo(source);
    }

    private DeviceEventEnvelope connected(DeviceConnectedEvent event) {
        return eventEnvelope(event.eventId(), event.deviceId(), event.occurredAt())
                .setConnected(DeviceConnectedEventPayload.newBuilder()
                        .setProtocol(event.protocol().name())
                        .setProtocolVersion(event.protocolVersion())
                        .setSessionId(event.sessionId())
                        .setRemoteAddress(event.remoteAddress()))
                .build();
    }

    private DeviceConnectedEvent connected(DeviceEventEnvelope envelope) {
        DeviceConnectedEventPayload payload = envelope.getConnected();
        return new DeviceConnectedEvent(eventId(envelope), deviceId(envelope),
                DeviceProtocol.valueOf(payload.getProtocol()), payload.getProtocolVersion(),
                payload.getSessionId(), payload.getRemoteAddress(), occurredAt(envelope));
    }

    private DeviceEventEnvelope heartbeat(HeartbeatEvent event) {
        return eventEnvelope(event.eventId(), event.deviceId(), event.occurredAt())
                .setHeartbeat(HeartbeatEventPayload.newBuilder()
                        .setSessionId(event.sessionId())
                        .setDeviceTimestampEpochMillis(event.deviceTime().toEpochMilli()))
                .build();
    }

    private HeartbeatEvent heartbeat(DeviceEventEnvelope envelope) {
        HeartbeatEventPayload payload = envelope.getHeartbeat();
        return new HeartbeatEvent(eventId(envelope), deviceId(envelope), payload.getSessionId(),
                Instant.ofEpochMilli(payload.getDeviceTimestampEpochMillis()), occurredAt(envelope));
    }

    private DeviceEventEnvelope connectorStatus(ConnectorStatusEvent event) {
        return eventEnvelope(event.eventId(), event.deviceId(), event.occurredAt())
                .setConnectorStatus(ConnectorStatusEventPayload.newBuilder()
                        .setConnector(ConnectorRef.newBuilder().setConnectorNo(event.connectorNo()))
                        .setStatus(event.status().name()))
                .build();
    }

    private ConnectorStatusEvent connectorStatus(DeviceEventEnvelope envelope) {
        ConnectorStatusEventPayload payload = envelope.getConnectorStatus();
        return new ConnectorStatusEvent(eventId(envelope), deviceId(envelope),
                payload.getConnector().getConnectorNo(), ConnectorStatus.valueOf(payload.getStatus()),
                occurredAt(envelope));
    }

    private DeviceEventEnvelope chargingProgress(ChargingProgressEvent event) {
        return eventEnvelope(event.eventId(), event.deviceId(), event.occurredAt())
                .setSourceRoute(route(event.protocol()))
                .setChargingProgress(ChargingProgressEventPayload.newBuilder()
                        .setConnector(ConnectorRef.newBuilder().setConnectorNo(event.connectorNo()))
                        .setTransactionId(event.transactionId())
                        .setOutputVoltage(decimal(event.outputVoltage()))
                        .setOutputCurrent(decimal(event.outputCurrent()))
                        .setOutputPowerKw(decimal(event.outputPowerKw()))
                        .setStateOfCharge(event.stateOfCharge())
                        .setEnergyKwh(decimal(event.energyKwh()))
                        .setChargingDurationMillis(event.chargingDuration().toMillis())
                        .setChargingAmount(decimal(event.chargingAmount()))
                        .setAdditionalInfo(event.additionalInfo()))
                .build();
    }

    private ChargingProgressEvent chargingProgress(DeviceEventEnvelope envelope) {
        ChargingProgressEventPayload payload = envelope.getChargingProgress();
        return new ChargingProgressEvent(eventId(envelope), deviceId(envelope),
                DeviceProtocol.valueOf(envelope.getSourceRoute().getProtocol().getFamily()),
                payload.getConnector().getConnectorNo(), payload.getTransactionId(),
                decimal(payload.getOutputVoltage()), decimal(payload.getOutputCurrent()),
                decimal(payload.getOutputPowerKw()),
                payload.hasStateOfCharge() ? payload.getStateOfCharge() : null,
                decimal(payload.getEnergyKwh()), Duration.ofMillis(payload.getChargingDurationMillis()),
                decimal(payload.getChargingAmount()), payload.getAdditionalInfo(), occurredAt(envelope));
    }

    private DeviceEventEnvelope commandResult(CommandResultEvent event) {
        CommandResultEventPayload.Builder payload = CommandResultEventPayload.newBuilder()
                .setCommandId(event.commandId().toString())
                .setCorrelationId(event.correlationId())
                .setProtocolTransactionId(event.protocolTransactionId())
                .setConnectorNo(event.connectorNo())
                .setCommandType(event.commandType())
                .setStatus(event.status().name())
                .setFailure(FailureDetail.newBuilder()
                        .setCode(event.failureCode())
                        .setReason(event.failureReason()))
                .setDuplicate(event.duplicate());
        if (event.rawResultCode() != null) payload.setRawResultCode(event.rawResultCode());
        if (event.rawFailureCode() != null) payload.setRawFailureCode(event.rawFailureCode());
        return eventEnvelope(event.eventId(), event.deviceId(), event.occurredAt())
                .setCommandResult(payload)
                .build();
    }

    private CommandResultEvent commandResult(DeviceEventEnvelope envelope) {
        CommandResultEventPayload payload = envelope.getCommandResult();
        return new CommandResultEvent(eventId(envelope), deviceId(envelope),
                UUID.fromString(payload.getCommandId()), payload.getCorrelationId(),
                payload.getProtocolTransactionId(), payload.getConnectorNo(), payload.getCommandType(),
                CommandResultStatus.valueOf(payload.getStatus()), payload.getFailure().getCode(),
                payload.getFailure().getReason(),
                payload.hasRawResultCode() ? payload.getRawResultCode() : null,
                payload.hasRawFailureCode() ? payload.getRawFailureCode() : null,
                payload.getDuplicate(), occurredAt(envelope));
    }

    private DeviceCommandEnvelope startCommand(StartChargingCommand command) {
        return commandEnvelope(command.commandId(), command.deviceId(), command.createdAt())
                .setStartCharging(StartChargingCommandPayload.newBuilder()
                        .setConnectorNo(command.connectorNo())
                        .setLimitAmount(decimal(command.limitAmount()))
                        .setLogicalCardNo(command.logicalCardNo())
                        .setPhysicalCardNo(command.physicalCardNo())
                        .setBusinessRef(command.businessRef()))
                .build();
    }

    private StartChargingCommand startCommand(DeviceCommandEnvelope envelope) {
        StartChargingCommandPayload payload = envelope.getStartCharging();
        return new StartChargingCommand(commandId(envelope), new DeviceId(envelope.getDeviceId()),
                payload.getConnectorNo(), decimal(payload.getLimitAmount()), payload.getLogicalCardNo(),
                payload.getPhysicalCardNo(), payload.getBusinessRef(), createdAt(envelope));
    }

    private DeviceCommandEnvelope stopCommand(StopChargingCommand command) {
        return commandEnvelope(command.commandId(), command.deviceId(), command.createdAt())
                .setStopCharging(StopChargingCommandPayload.newBuilder()
                        .setConnector(ConnectorRef.newBuilder()
                                .setConnectorId(command.connectorId().value())
                                .setConnectorNo(Integer.parseInt(command.connectorNo())))
                        .setTransactionId(command.transactionId().toString())
                        .setProtocolTransactionId(command.protocolTransactionId()))
                .build();
    }

    private StopChargingCommand stopCommand(DeviceCommandEnvelope envelope) {
        StopChargingCommandPayload payload = envelope.getStopCharging();
        return new StopChargingCommand(commandId(envelope), new DeviceId(envelope.getDeviceId()),
                new ConnectorId(payload.getConnector().getConnectorId()),
                String.format("%02d", payload.getConnector().getConnectorNo()),
                UUID.fromString(payload.getTransactionId()), payload.getProtocolTransactionId(), createdAt(envelope));
    }

    private DeviceEventEnvelope.Builder eventEnvelope(UUID eventId, DeviceId deviceId, Instant occurredAt) {
        return DeviceEventEnvelope.newBuilder()
                .setMetadata(metadata(eventId, occurredAt))
                .setDeviceId(deviceId.value());
    }

    private DeviceCommandEnvelope.Builder commandEnvelope(UUID commandId, DeviceId deviceId, Instant createdAt) {
        return DeviceCommandEnvelope.newBuilder()
                .setMetadata(metadata(commandId, createdAt))
                .setDeviceId(deviceId.value());
    }

    private MessageMetadata metadata(UUID messageId, Instant occurredAt) {
        return MessageMetadata.newBuilder()
                .setMessageId(messageId.toString())
                .setOccurredAtEpochMillis(occurredAt.toEpochMilli())
                .setSchemaVersion(1)
                .build();
    }

    private GatewayRoute route(DeviceProtocol protocol) {
        return GatewayRoute.newBuilder()
                .setGatewayNodeId("gateway-1")
                .setSessionId("session-1")
                .setConnectionEpoch(1)
                .setProtocol(ProtocolRef.newBuilder().setFamily(protocol.name()))
                .build();
    }

    private UUID eventId(DeviceEventEnvelope envelope) {
        return UUID.fromString(envelope.getMetadata().getMessageId());
    }

    private DeviceId deviceId(DeviceEventEnvelope envelope) {
        return new DeviceId(envelope.getDeviceId());
    }

    private Instant occurredAt(DeviceEventEnvelope envelope) {
        return Instant.ofEpochMilli(envelope.getMetadata().getOccurredAtEpochMillis());
    }

    private UUID commandId(DeviceCommandEnvelope envelope) {
        return UUID.fromString(envelope.getMetadata().getMessageId());
    }

    private Instant createdAt(DeviceCommandEnvelope envelope) {
        return Instant.ofEpochMilli(envelope.getMetadata().getOccurredAtEpochMillis());
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }
}
