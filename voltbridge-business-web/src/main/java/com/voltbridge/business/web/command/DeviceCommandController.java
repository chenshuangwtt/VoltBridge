package com.voltbridge.business.web.command;

import com.voltbridge.business.application.command.GetDeviceCommandUseCase;
import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/commands")
@Tag(name = "Commands", description = "Asynchronous DeviceCommand lifecycle queries")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successful response", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid command identifier or business rejection; response uses the ApiResponse failure envelope",
                content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class)))
})
public class DeviceCommandController {

    private final GetDeviceCommandUseCase getCommand;

    public DeviceCommandController(GetDeviceCommandUseCase getCommand) {
        this.getCommand = getCommand;
    }

    @GetMapping("/{commandId}")
    @Operation(summary = "Get a device command", description = "Returns command state and acknowledgement timestamps. SENT means the command was written to the transport; it does not mean the device completed it.")
    public ApiResponse<CommandView> find(@PathVariable UUID commandId) {
        return ApiResponse.success(CommandView.from(getCommand.execute(commandId)));
    }

    @Schema(description = "DeviceCommand status: CREATED, SENT, ACKED, EXECUTING, SUCCEEDED, FAILED, or TIMEOUT")
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
