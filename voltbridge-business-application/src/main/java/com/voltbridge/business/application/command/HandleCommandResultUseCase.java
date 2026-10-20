package com.voltbridge.business.application.command;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.port.DeviceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 将协议层标准命令结果幂等地应用到持久化命令聚合。 */
@Service
public class HandleCommandResultUseCase implements DeviceEventHandler<CommandResultEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleCommandResultUseCase.class);

    private final DeviceCommandRepository commandRepository;

    public HandleCommandResultUseCase(DeviceCommandRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    @Override
    public Class<CommandResultEvent> eventType() {
        return CommandResultEvent.class;
    }

    @Override
    public void handle(CommandResultEvent result) {
        DeviceCommand command = commandRepository.findById(result.commandId()).orElse(null);
        if (command == null) {
            LOGGER.warn("Command result ignored because command is missing: commandId={}, correlationId={}",
                    result.commandId(), result.correlationId());
            return;
        }
        boolean changed = update(command, result);
        if (changed) {
            commandRepository.save(command);
            LOGGER.info("DeviceCommand result applied: commandId={}, deviceCode={}, connectorNo={}, status={}",
                    command.id(), command.deviceCode(), command.connectorNo(), command.status());
        } else {
            LOGGER.warn("Duplicate or late command result ignored: commandId={}, currentStatus={}, correlationId={}, duplicate={}",
                    command.id(), command.status(), result.correlationId(), result.duplicate());
        }
    }

    private boolean update(DeviceCommand command, CommandResultEvent result) {
        String response = "{\"correlationId\":\"" + result.correlationId()
                + "\",\"status\":\"" + result.status().name()
                + "\",\"rawResultCode\":" + jsonNumber(result.rawResultCode())
                + ",\"rawFailureCode\":" + jsonNumber(result.rawFailureCode()) + "}";
        if (result.status() == CommandResultStatus.FAILED) {
            return command.markAcknowledgementFailed(
                    result.failureCode(), result.failureReason(), response, result.occurredAt());
        }
        if (result.status() == CommandResultStatus.ACKED) {
            return command.markAcked(response, result.occurredAt());
        }
        if (result.status() == CommandResultStatus.EXECUTING) {
            return command.markExecuting(result.occurredAt());
        }
        if (result.status() == CommandResultStatus.SUCCEEDED) {
            // 对时和重启等协议只有一个确定结果帧；该帧同时代表设备已确认且动作完成。
            if (command.status() == com.voltbridge.business.domain.command.DeviceCommandStatus.SENT
                    && command.markAcked(response, result.occurredAt())) {
                command.markSucceeded(response, result.occurredAt());
                return true;
            }
            return command.markSucceeded(response, result.occurredAt());
        }
        return false;
    }

    private String jsonNumber(Integer value) {
        return value == null ? "null" : value.toString();
    }
}
