package com.voltbridge.business.application.device;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.command.ResetDeviceCommand;
import com.voltbridge.device.command.SynchronizeTimeCommand;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.port.DeviceCommandGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 平台对时和重启的统一业务入口。
 *
 * <p>创建与发送仍遵循既有 DeviceCommand 生命周期：传输写出后为 SENT，0x55/0x91 的规范结果事件
 * 再推进终态。业务层只构造规范命令，不知道云快充命令字或 TCP 会话。</p>
 */
@Service
public class DeviceOperationCommandUseCase {
    private final ChargingDeviceRepository deviceRepository;
    private final DeviceCommandRepository commandRepository;
    private final DeviceCommandGateway commandGateway;
    private final TimeProvider timeProvider;
    private final Duration timeout;

    public DeviceOperationCommandUseCase(ChargingDeviceRepository deviceRepository,
                                         DeviceCommandRepository commandRepository,
                                         DeviceCommandGateway commandGateway,
                                         TimeProvider timeProvider,
                                         @Value("${voltbridge.device.command.operation-timeout:PT30S}") Duration timeout) {
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.commandGateway = commandGateway;
        this.timeProvider = timeProvider;
        this.timeout = timeout;
    }

    public UUID synchronizeTime(String deviceCode, Instant targetTime) {
        Instant effectiveTime = targetTime == null ? timeProvider.now() : targetTime;
        return dispatch(deviceCode, DeviceCommandType.SYNCHRONIZE_TIME,
                "{\"targetTime\":\"" + effectiveTime + "\"}",
                (commandId, deviceId, now) -> new SynchronizeTimeCommand(commandId, deviceId, effectiveTime, now));
    }

    public UUID reset(String deviceCode, boolean whenIdle) {
        return dispatch(deviceCode, DeviceCommandType.RESET_DEVICE,
                "{\"whenIdle\":" + whenIdle + "}",
                (commandId, deviceId, now) -> new ResetDeviceCommand(commandId, deviceId, whenIdle, now));
    }

    private UUID dispatch(String deviceCode, DeviceCommandType type, String payload, OutboundFactory factory) {
        ChargingDevice device = deviceRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new VoltBridgeException("DEVICE_NOT_FOUND", "Device not found: " + deviceCode));
        Instant now = timeProvider.now();
        DeviceCommand candidate = DeviceCommand.create(device.tenantId(), device.id(), device.deviceCode(), null, null,
                type, device.deviceCode(), payload, now, now.plus(timeout));
        DeviceCommand command = commandRepository.saveOrGetActive(candidate);
        if (!command.id().equals(candidate.id())) {
            return command.id();
        }
        try {
            commandGateway.dispatch(factory.create(command.id(), new DeviceId(device.deviceCode()), now))
                    .whenComplete((ignored, error) -> completeDispatch(command.id(), error));
        } catch (RuntimeException exception) {
            completeDispatch(command.id(), exception);
            throw exception;
        }
        return command.id();
    }

    private void completeDispatch(UUID commandId, Throwable error) {
        DeviceCommand command = commandRepository.findById(commandId).orElse(null);
        if (command == null) {
            return;
        }
        Instant now = timeProvider.now();
        if (error == null) {
            if (command.markSent(now)) {
                commandRepository.save(command);
            }
            return;
        }
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String code = cause instanceof VoltBridgeException exception ? exception.code() : "TRANSPORT_SEND_FAILED";
        if (command.markFailed(code, cause.getMessage(), "{}", now)) {
            commandRepository.save(command);
        }
    }

    @FunctionalInterface
    private interface OutboundFactory {
        com.voltbridge.device.command.DeviceCommand create(UUID commandId, DeviceId deviceId, Instant now);
    }
}
