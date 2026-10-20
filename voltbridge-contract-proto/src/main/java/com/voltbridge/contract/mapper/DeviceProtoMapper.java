package com.voltbridge.contract.mapper;

import com.voltbridge.contract.v1.*;
import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.command.SynchronizeTimeCommand;
import com.voltbridge.device.command.ResetDeviceCommand;
import com.voltbridge.device.event.*;
import com.voltbridge.device.model.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 规范设备模型与跨服务 protobuf 信封之间的唯一转换器。
 *
 * <p>Kafka 与 gRPC 都通过此类传输规范命令/事件，禁止在这里引入任何厂商协议 DTO 或网络会话。</p>
 */
public final class DeviceProtoMapper {

    private static final String DEFAULT_TENANT = "default";

    private DeviceProtoMapper() {
    }

    public static DeviceCommandEnvelope toProto(DeviceCommand command) {
        DeviceCommandEnvelope.Builder envelope = DeviceCommandEnvelope.newBuilder()
                .setCommandId(command.commandId().toString())
                .setDeviceId(command.deviceId().value())
                .setMetadata(metadata(command.commandId(), command.createdAt()));
        if (command instanceof StartChargingCommand start) {
            envelope.setStartCharging(StartChargingCommandPayload.newBuilder()
                    .setConnectorNo(start.connectorNo())
                    .setLimitAmount(value(start.limitAmount()))
                    .setLogicalCardNo(value(start.logicalCardNo()))
                    .setPhysicalCardNo(value(start.physicalCardNo()))
                    .setBusinessRef(value(start.businessRef())));
        } else if (command instanceof StopChargingCommand stop) {
            envelope.setStopCharging(StopChargingCommandPayload.newBuilder()
                    .setConnector(ConnectorRef.newBuilder().setConnectorId(stop.connectorId().value())
                            .setConnectorNo(integer(stop.connectorNo())))
                    .setTransactionId(stop.transactionId().toString())
                    .setProtocolTransactionId(value(stop.protocolTransactionId())));
        } else if (command instanceof SynchronizeTimeCommand sync) {
            envelope.setSynchronizeTime(SynchronizeTimeCommandPayload.newBuilder()
                    .setTargetTimeEpochMillis(epoch(sync.targetTime())));
        } else if (command instanceof ResetDeviceCommand reset) {
            envelope.setResetDevice(ResetDeviceCommandPayload.newBuilder().setWhenIdle(reset.whenIdle()));
        } else {
            throw new IllegalArgumentException("Unsupported device command: " + command.getClass().getName());
        }
        return envelope.build();
    }

    public static DeviceCommand fromProto(DeviceCommandEnvelope envelope) {
        UUID commandId = UUID.fromString(envelope.getCommandId());
        DeviceId deviceId = new DeviceId(envelope.getDeviceId());
        Instant createdAt = instant(envelope.getMetadata().getOccurredAtEpochMillis());
        return switch (envelope.getCommandCase()) {
            case START_CHARGING -> new StartChargingCommand(commandId, deviceId,
                    envelope.getStartCharging().getConnectorNo(), decimal(envelope.getStartCharging().getLimitAmount()),
                    blankToNull(envelope.getStartCharging().getLogicalCardNo()),
                    blankToNull(envelope.getStartCharging().getPhysicalCardNo()),
                    blankToNull(envelope.getStartCharging().getBusinessRef()), createdAt);
            case STOP_CHARGING -> new StopChargingCommand(commandId, deviceId,
                    new ConnectorId(envelope.getStopCharging().getConnector().getConnectorId()),
                    String.valueOf(envelope.getStopCharging().getConnector().getConnectorNo()),
                    UUID.fromString(envelope.getStopCharging().getTransactionId()),
                    blankToNull(envelope.getStopCharging().getProtocolTransactionId()), createdAt);
            case SYNCHRONIZE_TIME -> new SynchronizeTimeCommand(commandId, deviceId,
                    instant(envelope.getSynchronizeTime().getTargetTimeEpochMillis()), createdAt);
            case RESET_DEVICE -> new ResetDeviceCommand(commandId, deviceId,
                    envelope.getResetDevice().getWhenIdle(), createdAt);
            case COMMAND_NOT_SET -> throw new IllegalArgumentException("Device command body is required");
        };
    }

    public static DeviceEventEnvelope toProto(com.voltbridge.device.event.DeviceEvent event) {
        DeviceEventEnvelope.Builder envelope = DeviceEventEnvelope.newBuilder()
                .setEventId(event.eventId().toString())
                .setDeviceId(event.deviceId().value())
                .setMetadata(metadata(event.eventId(), event.occurredAt()));
        if (event instanceof DeviceConnectedEvent connected) {
            envelope.setConnected(DeviceConnectedEventPayload.newBuilder().setProtocol(connected.protocol().name())
                    .setProtocolVersion(value(connected.protocolVersion())).setSessionId(connected.sessionId())
                    .setRemoteAddress(value(connected.remoteAddress())));
        } else if (event instanceof DeviceDisconnectedEvent disconnected) {
            envelope.setDisconnected(DeviceDisconnectedEventPayload.newBuilder().setReason(value(disconnected.reason()))
                    .setProtocol(disconnected.protocol().name()).setSessionId(disconnected.sessionId()));
        } else if (event instanceof HeartbeatEvent heartbeat) {
            envelope.setHeartbeat(HeartbeatEventPayload.newBuilder().setSessionId(value(heartbeat.sessionId()))
                    .setDeviceTimestampEpochMillis(epoch(heartbeat.deviceTime())));
        } else if (event instanceof ConnectorStatusEvent status) {
            envelope.setConnectorStatus(ConnectorStatusEventPayload.newBuilder()
                    .setConnector(connector(status.connectorNo())).setStatus(status.status().name()));
        } else if (event instanceof ChargingProgressEvent progress) {
            ChargingProgressEventPayload.Builder body = ChargingProgressEventPayload.newBuilder()
                    .setConnector(connector(progress.connectorNo())).setTransactionId(value(progress.transactionId()))
                    .setOutputVoltage(value(progress.outputVoltage())).setOutputCurrent(value(progress.outputCurrent()))
                    .setOutputPowerKw(value(progress.outputPowerKw())).setEnergyKwh(value(progress.energyKwh()))
                    .setChargingDurationMillis(progress.chargingDuration().toMillis())
                    .setChargingAmount(value(progress.chargingAmount())).setAdditionalInfo(value(progress.additionalInfo()))
                    .setProtocol(progress.protocol().name());
            if (progress.stateOfCharge() != null) body.setStateOfCharge(progress.stateOfCharge());
            envelope.setChargingProgress(body);
        } else if (event instanceof ChargingEndedEvent ended) {
            ChargingEndedEventPayload.Builder body = ChargingEndedEventPayload.newBuilder()
                    .setConnector(connector(ended.connectorNo())).setProtocolTransactionId(value(ended.protocolTransactionId()))
                    .setOutputEnergyKwh(value(ended.outputEnergyKwh())).setChargingDurationMillis(ended.chargingDuration().toMillis())
                    .setAdditionalInfo(value(ended.additionalInfo())).setChargerCode(value(ended.chargerCode()))
                    .setProtocol(ended.protocol().name());
            if (ended.endSoc() != null) body.setEndSoc(ended.endSoc());
            if (ended.minimumCellVoltage() != null) body.setMinimumCellVoltage(ended.minimumCellVoltage().intValue());
            if (ended.maximumCellVoltage() != null) body.setMaximumCellVoltage(ended.maximumCellVoltage().intValue());
            if (ended.minimumTemperature() != null) body.setMinimumTemperature(ended.minimumTemperature());
            if (ended.maximumTemperature() != null) body.setMaximumTemperature(ended.maximumTemperature());
            envelope.setChargingEnded(body);
        } else if (event instanceof TransactionRecordEvent record) {
            TransactionRecordEventPayload.Builder body = TransactionRecordEventPayload.newBuilder()
                    .setConnector(connector(record.connectorNo())).setProtocolTransactionId(value(record.protocolTransactionId()))
                    .setStartedAtEpochMillis(epoch(record.startedAt())).setEndedAtEpochMillis(epoch(record.endedAt()))
                    .setStartMeterKwh(value(record.startMeterKwh())).setEndMeterKwh(value(record.endMeterKwh()))
                    .setTotalEnergyKwh(value(record.totalEnergyKwh())).setTotalAmount(value(record.totalAmount()))
                    .setVehicleVin(value(record.vehicleVin())).setStartMethod(record.startMethod().name())
                    .setStopCategory(record.stopCategory().name()).setRawStopCode(record.rawStopCode())
                    .setRawStopDetail(value(record.rawStopDetail())).setTransactionTimeEpochMillis(epoch(record.transactionTime()))
                    .setRawRecord(value(record.rawRecord())).setAdditionalInfo(value(record.additionalInfo()))
                    .setProtocol(record.protocol().name());
            record.energyBreakdown().forEach(item -> body.addEnergyBreakdown(EnergyBreakdownItemPayload.newBuilder()
                    .setCategory(value(item.category())).setEnergyKwh(value(item.energyKwh()))
                    .setAmount(value(item.amount())).setUnitPrice(value(item.unitPrice()))));
            envelope.setTransactionRecord(body);
        } else if (event instanceof CommandResultEvent result) {
            CommandResultEventPayload.Builder body = CommandResultEventPayload.newBuilder()
                    .setCommandId(result.commandId().toString()).setCorrelationId(value(result.correlationId()))
                    .setProtocolTransactionId(value(result.protocolTransactionId())).setConnectorNo(value(result.connectorNo()))
                    .setCommandType(value(result.commandType())).setStatus(result.status().name()).setDuplicate(result.duplicate());
            if (result.failureCode() != null || result.failureReason() != null) {
                body.setFailure(FailureDetail.newBuilder().setCode(value(result.failureCode())).setReason(value(result.failureReason())));
            }
            if (result.rawResultCode() != null) body.setRawResultCode(result.rawResultCode());
            if (result.rawFailureCode() != null) body.setRawFailureCode(result.rawFailureCode());
            envelope.setCommandResult(body);
        } else if (event instanceof DeviceDiagnosticEvent diagnostic) {
            envelope.setDiagnostic(DeviceDiagnosticEventPayload.newBuilder()
                    .setConnectorNo(value(diagnostic.connectorNo())).setSeverity(value(diagnostic.severity()))
                    .setCategory(value(diagnostic.category())).setCode(value(diagnostic.code()))
                    .setDescription(value(diagnostic.description()))
                    .setProtocolTransactionId(value(diagnostic.protocolTransactionId()))
                    .setProtocolDetail(value(diagnostic.protocolDetail())));
        } else {
            throw new IllegalArgumentException("Unsupported device event: " + event.getClass().getName());
        }
        return envelope.build();
    }

    public static com.voltbridge.device.event.DeviceEvent fromProto(DeviceEventEnvelope envelope) {
        UUID eventId = UUID.fromString(envelope.getEventId());
        DeviceId deviceId = new DeviceId(envelope.getDeviceId());
        Instant occurredAt = instant(envelope.getMetadata().getOccurredAtEpochMillis());
        return switch (envelope.getEventCase()) {
            case CONNECTED -> new DeviceConnectedEvent(eventId, deviceId, protocol(envelope.getConnected().getProtocol()),
                    blankToNull(envelope.getConnected().getProtocolVersion()), envelope.getConnected().getSessionId(),
                    blankToNull(envelope.getConnected().getRemoteAddress()), occurredAt);
            case DISCONNECTED -> new DeviceDisconnectedEvent(eventId, deviceId, protocol(envelope.getDisconnected().getProtocol()),
                    envelope.getDisconnected().getSessionId(), blankToNull(envelope.getDisconnected().getReason()), occurredAt);
            case HEARTBEAT -> new HeartbeatEvent(eventId, deviceId, blankToNull(envelope.getHeartbeat().getSessionId()),
                    instant(envelope.getHeartbeat().getDeviceTimestampEpochMillis()), occurredAt);
            case CONNECTOR_STATUS -> new ConnectorStatusEvent(eventId, deviceId, envelope.getConnectorStatus().getConnector().getConnectorNo(),
                    enumValue(ConnectorStatus.class, envelope.getConnectorStatus().getStatus(), ConnectorStatus.UNKNOWN), occurredAt);
            case CHARGING_PROGRESS -> progress(eventId, deviceId, occurredAt, envelope.getChargingProgress());
            case CHARGING_ENDED -> ended(eventId, deviceId, occurredAt, envelope.getChargingEnded());
            case TRANSACTION_RECORD -> record(eventId, deviceId, occurredAt, envelope.getTransactionRecord());
            case COMMAND_RESULT -> result(eventId, deviceId, occurredAt, envelope.getCommandResult());
            case DIAGNOSTIC -> diagnostic(eventId, deviceId, occurredAt, envelope.getDiagnostic());
            case EVENT_NOT_SET -> throw new IllegalArgumentException("Device event body is required");
        };
    }

    public static DeviceAdmissionRequest toAdmissionRequest(DeviceConnectedEvent event) {
        return DeviceAdmissionRequest.newBuilder().setDeviceId(event.deviceId().value())
                .setMetadata(metadata(event.eventId(), event.occurredAt()))
                .setConnected(DeviceConnectedEventPayload.newBuilder().setProtocol(event.protocol().name())
                        .setProtocolVersion(value(event.protocolVersion())).setSessionId(event.sessionId())
                        .setRemoteAddress(value(event.remoteAddress()))).build();
    }

    public static DeviceConnectedEvent fromAdmissionRequest(DeviceAdmissionRequest request) {
        return new DeviceConnectedEvent(UUID.fromString(request.getMetadata().getMessageId()), new DeviceId(request.getDeviceId()),
                protocol(request.getConnected().getProtocol()), blankToNull(request.getConnected().getProtocolVersion()),
                request.getConnected().getSessionId(), blankToNull(request.getConnected().getRemoteAddress()),
                instant(request.getMetadata().getOccurredAtEpochMillis()));
    }

    private static ChargingProgressEvent progress(UUID eventId, DeviceId deviceId, Instant occurredAt, ChargingProgressEventPayload body) {
        return new ChargingProgressEvent(eventId, deviceId, protocol(body.getProtocol()), body.getConnector().getConnectorNo(),
                blankToNull(body.getTransactionId()), decimal(body.getOutputVoltage()), decimal(body.getOutputCurrent()), decimal(body.getOutputPowerKw()),
                body.hasStateOfCharge() ? body.getStateOfCharge() : null, decimal(body.getEnergyKwh()), Duration.ofMillis(body.getChargingDurationMillis()),
                decimal(body.getChargingAmount()), blankToNull(body.getAdditionalInfo()), occurredAt);
    }

    private static ChargingEndedEvent ended(UUID eventId, DeviceId deviceId, Instant occurredAt, ChargingEndedEventPayload body) {
        return new ChargingEndedEvent(eventId, deviceId, protocol(body.getProtocol()), body.getConnector().getConnectorNo(),
                blankToNull(body.getProtocolTransactionId()), body.hasEndSoc() ? body.getEndSoc() : null,
                body.hasMinimumCellVoltage() ? BigDecimal.valueOf(body.getMinimumCellVoltage()) : null,
                body.hasMaximumCellVoltage() ? BigDecimal.valueOf(body.getMaximumCellVoltage()) : null,
                body.hasMinimumTemperature() ? body.getMinimumTemperature() : null, body.hasMaximumTemperature() ? body.getMaximumTemperature() : null,
                Duration.ofMillis(body.getChargingDurationMillis()), decimal(body.getOutputEnergyKwh()), blankToNull(body.getChargerCode()),
                blankToNull(body.getAdditionalInfo()), occurredAt);
    }

    private static TransactionRecordEvent record(UUID eventId, DeviceId deviceId, Instant occurredAt, TransactionRecordEventPayload body) {
        List<EnergyBreakdownItem> items = body.getEnergyBreakdownList().stream().map(item -> new EnergyBreakdownItem(
                blankToNull(item.getCategory()), decimal(item.getEnergyKwh()), decimal(item.getAmount()), decimal(item.getUnitPrice()))).toList();
        return new TransactionRecordEvent(eventId, deviceId, protocol(body.getProtocol()), body.getConnector().getConnectorNo(),
                blankToNull(body.getProtocolTransactionId()), instant(body.getStartedAtEpochMillis()), instant(body.getEndedAtEpochMillis()),
                decimal(body.getStartMeterKwh()), decimal(body.getEndMeterKwh()), decimal(body.getTotalEnergyKwh()), decimal(body.getTotalAmount()),
                blankToNull(body.getVehicleVin()), enumValue(ChargingStartMethod.class, body.getStartMethod(), ChargingStartMethod.UNKNOWN),
                enumValue(ChargingStopCategory.class, body.getStopCategory(), ChargingStopCategory.UNKNOWN), body.getRawStopCode(),
                blankToNull(body.getRawStopDetail()), instant(body.getTransactionTimeEpochMillis()), items,
                blankToNull(body.getRawRecord()), blankToNull(body.getAdditionalInfo()), occurredAt);
    }

    private static CommandResultEvent result(UUID eventId, DeviceId deviceId, Instant occurredAt, CommandResultEventPayload body) {
        return new CommandResultEvent(eventId, deviceId, UUID.fromString(body.getCommandId()), blankToNull(body.getCorrelationId()),
                blankToNull(body.getProtocolTransactionId()), blankToNull(body.getConnectorNo()), blankToNull(body.getCommandType()),
                enumValue(CommandResultStatus.class, body.getStatus(), CommandResultStatus.FAILED),
                body.hasFailure() ? blankToNull(body.getFailure().getCode()) : null,
                body.hasFailure() ? blankToNull(body.getFailure().getReason()) : null,
                body.hasRawResultCode() ? body.getRawResultCode() : null, body.hasRawFailureCode() ? body.getRawFailureCode() : null,
                body.getDuplicate(), occurredAt);
    }

    private static DeviceDiagnosticEvent diagnostic(UUID eventId, DeviceId deviceId, Instant occurredAt,
                                                    DeviceDiagnosticEventPayload body) {
        return new DeviceDiagnosticEvent(eventId, deviceId, blankToNull(body.getConnectorNo()),
                blankToNull(body.getSeverity()), blankToNull(body.getCategory()), blankToNull(body.getCode()),
                blankToNull(body.getDescription()), blankToNull(body.getProtocolTransactionId()),
                blankToNull(body.getProtocolDetail()), occurredAt);
    }

    private static MessageMetadata metadata(UUID messageId, Instant occurredAt) {
        return MessageMetadata.newBuilder().setMessageId(messageId.toString()).setTenantId(DEFAULT_TENANT)
                .setOccurredAtEpochMillis(epoch(occurredAt)).setSchemaVersion(1).build();
    }

    private static ConnectorRef connector(int connectorNo) { return ConnectorRef.newBuilder().setConnectorNo(connectorNo).build(); }
    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static int integer(String value) { try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return 0; } }
    private static BigDecimal decimal(String value) { return value == null || value.isBlank() ? null : new BigDecimal(value); }
    private static long epoch(Instant instant) { return instant == null ? 0 : instant.toEpochMilli(); }
    private static Instant instant(long epochMillis) { return Instant.ofEpochMilli(epochMillis); }
    private static DeviceProtocol protocol(String value) { return enumValue(DeviceProtocol.class, value, DeviceProtocol.UNKNOWN); }
    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) { try { return Enum.valueOf(type, value); } catch (RuntimeException ignored) { return fallback; } }
}
