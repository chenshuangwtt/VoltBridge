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
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.StartChargingCommand;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.port.DeviceCommandGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * 远程启动充电的业务入口。
 *
 * <p>主流程依次完成设备/连接器校验、创建 {@link DeviceCommand}、创建处于
 * {@code STARTING} 的 {@link ChargingTransaction}，最后通过设备网关异步下发协议命令。
 * 命令和交易分别持久化，任何下发失败都会同步收敛两者的失败状态。该业务用例只产生
 * 平台级 {@link StartChargingCommand}，不选择云快充或 OCPP。</p>
 */
@Service
public class StartChargingUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartChargingUseCase.class);

    private final ChargingDeviceRepository deviceRepository;
    private final ConnectorRepository connectorRepository;
    private final DeviceCommandRepository commandRepository;
    private final ChargingTransactionRepository transactionRepository;
    private final DeviceCommandGateway commandGateway;
    private final TimeProvider timeProvider;
    private final Duration remoteStartTimeout;

    public StartChargingUseCase(ChargingDeviceRepository deviceRepository,
                                ConnectorRepository connectorRepository,
                                DeviceCommandRepository commandRepository,
                                ChargingTransactionRepository transactionRepository,
                                DeviceCommandGateway commandGateway,
                                TimeProvider timeProvider,
                                @Value("${voltbridge.device.command.remote-start-timeout:PT30S}")
                                Duration remoteStartTimeout) {
        this.deviceRepository = deviceRepository;
        this.connectorRepository = connectorRepository;
        this.commandRepository = commandRepository;
        this.transactionRepository = transactionRepository;
        this.commandGateway = commandGateway;
        this.timeProvider = timeProvider;
        this.remoteStartTimeout = remoteStartTimeout;
    }

    public UUID execute(String deviceCode, String connectorNo, BigDecimal limitAmount,
                        String logicalCardNo, String physicalCardNo, String businessRef) {
        // 阶段一：验证业务对象和请求约束，此时尚未创建命令或发送设备报文。
        ChargingDevice device = deviceRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new VoltBridgeException("DEVICE_NOT_FOUND", "Device not found: " + deviceCode));
        int parsedConnectorNo = parseConnectorNo(connectorNo);
        Connector connector = connectorRepository.findByDeviceIdAndConnectorNo(device.id(), parsedConnectorNo)
                .orElseThrow(() -> new VoltBridgeException("CONNECTOR_NOT_FOUND",
                        "Connector not found: " + deviceCode + "/" + connectorNo));
        validateRequest(limitAmount, logicalCardNo, physicalCardNo, businessRef);

        // 阶段二：先持久化 CREATED 命令和 STARTING 交易，避免设备快速响应时找不到业务关联。
        Instant now = timeProvider.now();
        String normalizedConnectorNo = String.format("%02d", parsedConnectorNo);
        DeviceCommand command = DeviceCommand.create(device.tenantId(), device.id(), deviceCode,
                connector.id(), normalizedConnectorNo, DeviceCommandType.START_CHARGING,
                businessRef, requestPayload(normalizedConnectorNo, limitAmount,
                        logicalCardNo, physicalCardNo), now, now.plus(remoteStartTimeout));
        commandRepository.save(command);
        ChargingTransaction transaction = ChargingTransaction.start(device.tenantId(), device.id(), deviceCode,
                connector.id(), parsedConnectorNo, device.protocol(), command.id(), command.businessRef(), now);
        try {
            transactionRepository.save(transaction);
        } catch (RuntimeException exception) {
            command.markFailed("TRANSACTION_CREATE_FAILED", exception.getMessage(), "{}", timeProvider.now());
            commandRepository.save(command);
            throw exception;
        }
        LOGGER.info("DeviceCommand created: commandId={}, deviceCode={}, connectorNo={}, status=CREATED",
                command.id(), deviceCode, normalizedConnectorNo);
        LOGGER.info("ChargingTransaction created: transactionId={}, commandId={}, deviceCode={}, connectorNo={}, status=STARTING",
                transaction.id(), command.id(), deviceCode, normalizedConnectorNo);

        // 阶段三：业务只构造 canonical command；协议选择、编码和会话定位都属于 DeviceGateway。
        StartChargingCommand outbound = new StartChargingCommand(command.id(), new DeviceId(deviceCode),
                normalizedConnectorNo, limitAmount, logicalCardNo, physicalCardNo,
                command.businessRef(), now);
        try {
            // 阶段四：传输写成功才进入 SENT；设备 ACK 与真实开始充电由后续事件分别确认。
            CompletionStage<Void> dispatch = commandGateway.dispatch(outbound);
            dispatch.whenComplete((ignored, error) -> updateDispatchResult(command.id(), error));
        } catch (RuntimeException exception) {
            failBeforeDispatch(command, exception);
            failTransaction(command.id(), exception.getMessage());
            throw exception;
        }
        return command.id();
    }

    private void updateDispatchResult(UUID commandId, Throwable error) {
        DeviceCommand command = commandRepository.findById(commandId).orElse(null);
        if (command == null) {
            LOGGER.error("DeviceCommand disappeared before dispatch result: commandId={}", commandId);
            return;
        }
        Instant now = timeProvider.now();
        if (error == null) {
            // 网络写成功 != 设备接受，不能在这里进入 ACKED 或 SUCCEEDED。
            if (command.markSent(now)) {
                commandRepository.save(command);
                LOGGER.info("DeviceCommand sent: commandId={}, deviceCode={}, connectorNo={}",
                        command.id(), command.deviceCode(), command.connectorNo());
            }
            return;
        }
        Throwable cause = unwrap(error);
        String code = cause instanceof VoltBridgeException exception
                ? exception.code() : "TRANSPORT_WRITE_FAILED";
        // 路由/编码/写出失败意味着命令未被设备可靠接收，启动交易也不能继续等待充电证据。
        if (command.markFailed(code, cause.getMessage(), "{}", now)) {
            commandRepository.save(command);
            failTransaction(command.id(), cause.getMessage());
            LOGGER.warn("DeviceCommand send failed: commandId={}, deviceCode={}, connectorNo={}, code={}, reason={}",
                    command.id(), command.deviceCode(), command.connectorNo(), code, cause.getMessage());
        }
    }

    private void failTransaction(UUID commandId, String reason) {
        transactionRepository.findByCommandId(commandId).ifPresent(transaction -> {
            if (transaction.markStartFailed(ChargingStopCategory.OTHER, null, reason, timeProvider.now())) {
                transactionRepository.save(transaction);
            }
        });
    }

    private void failBeforeDispatch(DeviceCommand command, RuntimeException exception) {
        String code = exception instanceof VoltBridgeException voltBridgeException
                ? voltBridgeException.code() : "DISPATCH_FAILED";
        command.markFailed(code, exception.getMessage(), "{}", timeProvider.now());
        commandRepository.save(command);
        LOGGER.warn("DeviceCommand dispatch rejected: commandId={}, deviceCode={}, connectorNo={}, code={}",
                command.id(), command.deviceCode(), command.connectorNo(), code);
    }

    private int parseConnectorNo(String connectorNo) {
        try {
            int value = Integer.parseInt(connectorNo);
            if (value < 1 || value > 99) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new VoltBridgeException("INVALID_CONNECTOR_NO", "Connector number must be between 01 and 99");
        }
    }

    private void validateRequest(BigDecimal limitAmount, String logicalCardNo,
                                 String physicalCardNo, String businessRef) {
        if (limitAmount == null || limitAmount.signum() < 0
                || limitAmount.movePointRight(2).stripTrailingZeros().scale() > 0
                || limitAmount.compareTo(new BigDecimal("21474836.47")) > 0) {
            throw new VoltBridgeException("INVALID_LIMIT_AMOUNT", "Limit amount must be valid to two decimal places");
        }
        validateDigits("logicalCardNo", logicalCardNo, 16);
        validateDigits("physicalCardNo", physicalCardNo, 18);
        if (businessRef != null && businessRef.length() > 128) {
            throw new VoltBridgeException("INVALID_BUSINESS_REF", "Business reference exceeds 128 characters");
        }
    }

    private void validateDigits(String field, String value, int maxLength) {
        if (value != null && (!value.matches("\\d{1," + maxLength + "}"))) {
            throw new VoltBridgeException("INVALID_CARD_NO", field + " must contain at most " + maxLength + " digits");
        }
    }

    private String requestPayload(String connectorNo, BigDecimal limitAmount,
                                  String logicalCardNo, String physicalCardNo) {
        return "{\"connectorNo\":\"" + connectorNo + "\",\"limitAmount\":"
                + limitAmount.toPlainString() + ",\"logicalCardNo\":" + jsonValue(logicalCardNo)
                + ",\"physicalCardNo\":" + jsonValue(physicalCardNo) + "}";
    }

    private String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
