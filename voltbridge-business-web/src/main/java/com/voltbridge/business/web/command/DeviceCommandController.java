package com.voltbridge.business.web.command;

import com.voltbridge.business.application.command.GetDeviceCommandUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/commands")
public class DeviceCommandController {

    private final GetDeviceCommandUseCase getCommand;

    public DeviceCommandController(GetDeviceCommandUseCase getCommand) {
        this.getCommand = getCommand;
    }

    @GetMapping("/{commandId}")
    public ApiResponse<CommandView> find(@PathVariable UUID commandId) {
        return ApiResponse.success(CommandView.from(getCommand.execute(commandId)));
    }

    public record CommandView(UUID commandId, String deviceCode, String connectorNo,
                              String type, String status, String businessRef,
                              Instant createdAt, Instant sentAt, Instant ackedAt, Instant completedAt,
                              String failureCode, String failureReason) {

        private static CommandView from(DeviceCommand command) {
            return new CommandView(command.id(), command.deviceCode(), command.connectorNo(),
                    command.type().name(), command.status().name(),
                    command.businessRef(), command.createdAt(), command.sentAt(), command.ackedAt(),
                    command.completedAt(), command.failureCode(), command.failureReason());
        }
    }
}
