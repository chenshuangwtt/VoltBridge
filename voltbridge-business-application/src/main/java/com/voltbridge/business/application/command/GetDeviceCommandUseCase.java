package com.voltbridge.business.application.command;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GetDeviceCommandUseCase {

    private final DeviceCommandRepository commandRepository;

    public GetDeviceCommandUseCase(DeviceCommandRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    public DeviceCommand execute(UUID commandId) {
        return commandRepository.findById(commandId)
                .orElseThrow(() -> new VoltBridgeException("COMMAND_NOT_FOUND", "Command not found: " + commandId));
    }
}
