package com.voltbridge.business.application.charging;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.StopChargingCommand;
import com.voltbridge.device.model.ConnectorId;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.port.DeviceCommandGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 平台远程停止充电的业务入口。
 *
 * <p>主流程按设备和枪号定位唯一活动交易，只允许 {@code STARTING/CHARGING} 交易停充；
 * 随后创建可持久化命令，并复用统一设备网关完成路由、协议编码和传输下发。协议确认只
 * 推进命令到 ACKED，交易结束与最终完成仍由标准结束事件和最终交易记录驱动。业务层
 * 不区分云快充 {@code 0x36} 与 OCPP {@code RemoteStopTransaction}。</p>
 */
@Service
public class StopChargingUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(StopChargingUseCase.class);

    private final ChargingDeviceRepository deviceRepository;
    private final ConnectorRepository connectorRepository;
    private final ChargingTransactionRepository transactionRepository;
    private final DeviceCommandRepository commandRepository;
    private final DeviceCommandGateway commandGateway;
    private final TimeProvider timeProvider;
    private final Duration remoteStopTimeout;

    public StopChargingUseCase(ChargingDeviceRepository deviceRepository,
                               ConnectorRepository connectorRepository,
                               ChargingTransactionRepository transactionRepository,
                               DeviceCommandRepository commandRepository,
                               DeviceCommandGateway commandGateway,
                               TimeProvider timeProvider,
                               @Value("${voltbridge.device.command.remote-stop-timeout:PT30S}")
                               Duration remoteStopTimeout) {
        this.deviceRepository = deviceRepository;
        this.connectorRepository = connectorRepository;
        this.transactionRepository = transactionRepository;
        this.commandRepository = commandRepository;
        this.commandGateway = commandGateway;
        this.timeProvider = timeProvider;
        this.remoteStopTimeout = remoteStopTimeout;
    }

    public synchronized UUID execute(String deviceCode, String connectorNo) {
        // 阶段一：停充目标是平台活动交易，而不是调用方传入的协议流水号。
        ChargingDevice device = deviceRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new VoltBridgeException("DEVICE_NOT_FOUND", "Device not found: " + deviceCode));
        int parsedConnectorNo = parseConnectorNo(connectorNo);
        Connector connector = connectorRepository.findByDeviceIdAndConnectorNo(device.id(), parsedConnectorNo)
                .orElseThrow(() -> new VoltBridgeException("CONNECTOR_NOT_FOUND",
                        "Connector not found: " + deviceCode + "/" + connectorNo));
        ChargingTransaction transaction = transactionRepository
                .findActiveByDeviceAndConnector(device.id(), connector.id())
                .filter(this::stoppable)
                .orElseThrow(() -> new VoltBridgeException("NO_ACTIVE_TRANSACTION",
                        "No stoppable charging transaction: " + deviceCode + "/" + connectorNo));

        // 阶段二：交易 UUID 是停充命令的业务幂等键；数据库唯一索引负责并发最终兜底。
        Instant now = timeProvider.now();
        String normalizedConnectorNo = String.format("%02d", parsedConnectorNo);
        DeviceCommand candidate = DeviceCommand.create(device.tenantId(), device.id(), deviceCode,
                connector.id(), normalizedConnectorNo, DeviceCommandType.STOP_CHARGING,
                transaction.id().toString(), requestPayload(normalizedConnectorNo, transaction),
                now, now.plus(remoteStopTimeout));
        DeviceCommand command = commandRepository.saveOrGetActive(candidate);
        if (!command.id().equals(candidate.id())) {
            // 已有活动停充命令时直接复用，避免向设备重复发送控制请求。
            LOGGER.info("Duplicate stop request reused: commandId={}, transactionId={}, deviceCode={}, connectorNo={}",
                    command.id(), transaction.id(), deviceCode, normalizedConnectorNo);
            return command.id();
        }
        LOGGER.info("Stop DeviceCommand created: commandId={}, transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                command.id(), transaction.id(), transaction.protocolTransactionId(),
                deviceCode, normalizedConnectorNo);

        // 阶段三：canonical command 同时携带平台交易 ID 与协议交易 ID，各协议按需编码。
        StopChargingCommand outbound = new StopChargingCommand(command.id(), new DeviceId(deviceCode),
                new ConnectorId(connector.id().toString()), normalizedConnectorNo,
                transaction.id(), transaction.protocolTransactionId(), now);
        try {
            // 阶段四：写成功只表示 SENT；ACKED 与真实停止 SUCCEEDED 是两个独立事实。
            CompletionStage<Void> dispatch = commandGateway.dispatch(outbound);
            dispatch.whenComplete((ignored, error) -> updateDispatchResult(command.id(), error));
        } catch (RuntimeException exception) {
            failBeforeDispatch(command, exception);
            throw exception;
        }
        return command.id();
    }

    private boolean stoppable(ChargingTransaction transaction) {
        return transaction.status() == ChargingTransactionStatus.STARTING
                || transaction.status() == ChargingTransactionStatus.CHARGING;
    }

    private void updateDispatchResult(UUID commandId, Throwable error) {
        DeviceCommand command = commandRepository.findById(commandId).orElse(null);
        if (command == null) {
            LOGGER.error("Stop DeviceCommand disappeared before dispatch result: commandId={}", commandId);
            return;
        }
        Instant now = timeProvider.now();
        if (error == null) {
            // 网络写成功不能提前结束交易，也不能把连接器状态人工改为可用。
            if (command.markSent(now)) {
                commandRepository.save(command);
                LOGGER.info("Stop command sent: commandId={}, transactionId={}, deviceCode={}, connectorNo={}",
                        command.id(), command.businessRef(), command.deviceCode(), command.connectorNo());
            }
            return;
        }
        Throwable cause = unwrap(error);
        String code = cause instanceof VoltBridgeException exception
                ? exception.code() : "TRANSPORT_WRITE_FAILED";
        if (command.markFailed(code, cause.getMessage(), "{}", now)) {
            commandRepository.save(command);
            LOGGER.warn("Stop command send failed: commandId={}, transactionId={}, code={}, reason={}",
                    command.id(), command.businessRef(), code, cause.getMessage());
        }
    }

    private void failBeforeDispatch(DeviceCommand command, RuntimeException exception) {
        String code = exception instanceof VoltBridgeException voltBridgeException
                ? voltBridgeException.code() : "DISPATCH_FAILED";
        command.markFailed(code, exception.getMessage(), "{}", timeProvider.now());
        commandRepository.save(command);
        LOGGER.warn("Stop command dispatch rejected: commandId={}, transactionId={}, code={}",
                command.id(), command.businessRef(), code);
    }

    private int parseConnectorNo(String connectorNo) {
        try {
            int value = Integer.parseInt(connectorNo);
            if (value < 1 || value > 99) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new VoltBridgeException("INVALID_CONNECTOR_NO",
                    "Connector number must be between 01 and 99");
        }
    }

    private String requestPayload(String connectorNo, ChargingTransaction transaction) {
        return "{\"connectorNo\":\"" + connectorNo + "\",\"transactionId\":\""
                + transaction.id() + "\",\"protocolTransactionId\":"
                + jsonValue(transaction.protocolTransactionId()) + "}";
    }

    private String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
