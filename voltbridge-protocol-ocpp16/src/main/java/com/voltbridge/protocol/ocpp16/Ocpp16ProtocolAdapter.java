package com.voltbridge.protocol.ocpp16;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolAdapter;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.action.Ocpp16ActionHandlerRegistry;
import com.voltbridge.protocol.ocpp16.codec.Ocpp16FrameCodec;
import com.voltbridge.protocol.ocpp16.correlation.Ocpp16PendingRequestRegistry;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallError;
import com.voltbridge.protocol.ocpp16.message.Ocpp16CallResult;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Message;
import com.voltbridge.transport.api.TextTransportPayload;
import com.voltbridge.transport.api.TransportPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * OCPP 1.6J 文本消息与统一设备命令/事件之间的协议适配器入口。
 *
 * <p>入站边界：WebSocket 文本 -> OCPP CALL/CALLRESULT/CALLERROR -> 标准事件或认证请求；
 * 出站边界：标准命令 -> OCPP CALL -> 文本载荷。uniqueId 关联仅归本协议模块所有，业务层
 * 只使用 {@code DeviceCommand.id} 和 {@code ChargingTransaction.id}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "voltbridge.protocol.ocpp16", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class Ocpp16ProtocolAdapter implements ProtocolAdapter {

    public static final String ENDPOINT_ID = "ocpp16-websocket";
    private static final Logger LOGGER = LoggerFactory.getLogger(Ocpp16ProtocolAdapter.class);

    private final Ocpp16FrameCodec codec;
    private final Ocpp16ActionHandlerRegistry actionHandlers;
    private final Ocpp16PendingRequestRegistry pendingRequests;
    private final TimeProvider timeProvider;

    public Ocpp16ProtocolAdapter(Ocpp16FrameCodec codec, Ocpp16ActionHandlerRegistry actionHandlers,
                                 Ocpp16PendingRequestRegistry pendingRequests, TimeProvider timeProvider) {
        this.codec = codec;
        this.actionHandlers = actionHandlers;
        this.pendingRequests = pendingRequests;
        this.timeProvider = timeProvider;
    }

    @Override public String endpointId() { return ENDPOINT_ID; }
    @Override public DeviceProtocol protocol() { return DeviceProtocol.OCPP_16; }

    @Override
    public ProtocolInboundResult decode(ProtocolContext context, TransportPayload frame) {
        if (!(frame instanceof TextTransportPayload text)) {
            throw new IllegalArgumentException("OCPP 1.6J requires WebSocket text payloads");
        }
        try {
            // RAW PROTOCOL -> OCPP message DTO，JSON 数组结构在此边界内终止。
            Ocpp16Message message = codec.decode(text.value());
            if (message instanceof Ocpp16Call call) {
                LOGGER.info("OCPP CALL received: sessionId={}, deviceCode={}, uniqueId={}, action={}",
                        context.sessionId(), deviceCode(context), call.uniqueId(), call.action());
                // CALL action -> canonical event/authentication request + CALLRESULT payload。
                return actionHandlers.route(context, call);
            }
            // CALLRESULT/CALLERROR 使用 uniqueId 找回平台 commandId，再产生标准命令结果事件。
            if (message instanceof Ocpp16CallResult result) return handleResult(result);
            return handleError((Ocpp16CallError) message);
        } catch (Ocpp16Exception exception) {
            LOGGER.warn("OCPP CALL rejected: sessionId={}, uniqueId={}, errorCode={}, reason={}",
                    context.sessionId(), exception.uniqueId(), exception.errorCode(), exception.getMessage());
            ObjectNode details = codec.objectMapper().createObjectNode();
            String response = codec.encode(new Ocpp16CallError(exception.uniqueId(),
                    exception.errorCode(), exception.getMessage(), details));
            return new ProtocolInboundResult(List.of(), List.of(new TextTransportPayload(response)),
                    java.util.Optional.empty());
        } catch (RuntimeException exception) {
            LOGGER.error("OCPP processing failed: sessionId={}", context.sessionId(), exception);
            ObjectNode details = codec.objectMapper().createObjectNode();
            String response = codec.encode(new Ocpp16CallError("unknown", "InternalError",
                    "Internal processing error", details));
            return new ProtocolInboundResult(List.of(), List.of(new TextTransportPayload(response)),
                    java.util.Optional.empty());
        }
    }

    @Override
    public TransportPayload encode(ProtocolContext context, DeviceCommand command) {
        // uniqueId 是 OCPP 单次 CALL 关联键，不是平台命令 ID 或 OCPP transactionId。
        String uniqueId = UUID.randomUUID().toString();
        if (command instanceof StartChargingCommand start) {
            ObjectNode payload = codec.objectMapper().createObjectNode();
            String idTag = start.logicalCardNo() == null ? start.businessRef() : start.logicalCardNo();
            if (idTag == null || idTag.isBlank() || idTag.length() > 20) {
                throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation",
                        "RemoteStartTransaction requires idTag with at most 20 characters");
            }
            payload.put("idTag", idTag);
            payload.put("connectorId", Integer.parseInt(start.connectorNo()));
            // 先登记 uniqueId -> commandId，再返回 CALL，避免快速响应到达时丢失关联。
            pendingRequests.register(uniqueId, start.commandId(), start.deviceId(),
                    start.connectorNo(), "START_CHARGING", "RemoteStartTransaction");
            LOGGER.info("OCPP RemoteStartTransaction encoded: commandId={}, deviceCode={}, connectorNo={}, uniqueId={}",
                    start.commandId(), start.deviceId().value(), start.connectorNo(), uniqueId);
            return new TextTransportPayload(codec.encode(
                    new Ocpp16Call(uniqueId, "RemoteStartTransaction", payload)));
        }
        if (command instanceof StopChargingCommand stop) {
            if (stop.protocolTransactionId() == null || !stop.protocolTransactionId().matches("\\d+")) {
                throw new Ocpp16Exception(uniqueId, "PropertyConstraintViolation",
                        "RemoteStopTransaction requires numeric protocol transaction id");
            }
            // OCPP transactionId 来自 StartTransaction 响应，与平台 ChargingTransaction.id 不同。
            ObjectNode payload = codec.objectMapper().createObjectNode();
            payload.put("transactionId", Integer.parseInt(stop.protocolTransactionId()));
            pendingRequests.register(uniqueId, stop.commandId(), stop.deviceId(),
                    stop.connectorNo(), "STOP_CHARGING", "RemoteStopTransaction");
            LOGGER.info("OCPP RemoteStopTransaction encoded: commandId={}, transactionId={}, deviceCode={}, uniqueId={}",
                    stop.commandId(), stop.protocolTransactionId(), stop.deviceId().value(), uniqueId);
            return new TextTransportPayload(codec.encode(
                    new Ocpp16Call(uniqueId, "RemoteStopTransaction", payload)));
        }
        throw new Ocpp16Exception(uniqueId, "NotSupported",
                "Unsupported OCPP command: " + command.getClass().getSimpleName());
    }

    private ProtocolInboundResult handleResult(Ocpp16CallResult result) {
        Ocpp16PendingRequestRegistry.Resolution resolution = pendingRequests.resolve(result.uniqueId()).orElse(null);
        if (resolution == null) {
            LOGGER.warn("Unknown or late OCPP CALLRESULT ignored: uniqueId={}", result.uniqueId());
            return ProtocolInboundResult.empty();
        }
        String status = result.payload().path("status").asText("Rejected");
        boolean accepted = "Accepted".equals(status);
        // Accepted 只推进业务命令到 ACKED；StartTransaction/StopTransaction 才证明真实效果。
        CommandResultEvent event = new CommandResultEvent(UUID.randomUUID(), resolution.deviceId(),
                resolution.commandId(), result.uniqueId(), null, resolution.connectorNo(),
                resolution.commandType(), accepted ? CommandResultStatus.ACKED : CommandResultStatus.FAILED,
                accepted ? null : "COMMAND_REJECTED", accepted ? null : resolution.action() + " rejected",
                null, null, resolution.duplicate(), timeProvider.now());
        LOGGER.info("OCPP CALLRESULT received: commandId={}, uniqueId={}, action={}, status={}, duplicate={}",
                resolution.commandId(), result.uniqueId(), resolution.action(), status, resolution.duplicate());
        return ProtocolInboundResult.events(List.of(event));
    }

    private ProtocolInboundResult handleError(Ocpp16CallError error) {
        Ocpp16PendingRequestRegistry.Resolution resolution = pendingRequests.resolve(error.uniqueId()).orElse(null);
        if (resolution == null) {
            LOGGER.warn("Unknown or late OCPP CALLERROR ignored: uniqueId={}, errorCode={}",
                    error.uniqueId(), error.errorCode());
            return ProtocolInboundResult.empty();
        }
        CommandResultEvent event = new CommandResultEvent(UUID.randomUUID(), resolution.deviceId(),
                resolution.commandId(), error.uniqueId(), null, resolution.connectorNo(),
                resolution.commandType(), CommandResultStatus.FAILED,
                "OCPP_" + error.errorCode().toUpperCase(), error.errorDescription(),
                null, null, resolution.duplicate(), timeProvider.now());
        return ProtocolInboundResult.events(List.of(event));
    }

    private String deviceCode(ProtocolContext context) {
        return context.deviceId().map(device -> device.value())
                .orElse(context.transportAttributes().getOrDefault("deviceCode", "unbound"));
    }
}
