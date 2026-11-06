package com.voltbridge.business.web.device;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.device.FindChargingDeviceUseCase;
import com.voltbridge.business.application.device.DeviceOperationCommandUseCase;
import com.voltbridge.business.application.device.DeviceOperationPreconditions;
import com.voltbridge.business.application.device.FindDeviceOperationCapabilitiesUseCase;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.DeviceBusinessStatus;
import com.voltbridge.common.result.ApiResponse;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.model.DeviceOperationCapabilities;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 设备查询及远程启停命令的 REST 输入适配器，不承载业务规则。 */
@RestController
@RequestMapping("/api/devices")
@Tag(name = "Devices", description = "Device lookup and asynchronous remote operations")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successful response", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input or business rule rejection; response uses the ApiResponse failure envelope",
                content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = ApiResponse.class)))
})
public class DeviceController {

    private final FindChargingDeviceUseCase findDevice;
    private final StartChargingUseCase startCharging;
    private final StopChargingUseCase stopCharging;
    private final DeviceOperationCommandUseCase operations;
    private final DeviceOperationPreconditions operationPreconditions;
    private final FindDeviceOperationCapabilitiesUseCase findCapabilities;

    @org.springframework.beans.factory.annotation.Autowired
    public DeviceController(FindChargingDeviceUseCase findDevice, StartChargingUseCase startCharging,
                            StopChargingUseCase stopCharging, DeviceOperationCommandUseCase operations,
                            DeviceOperationPreconditions operationPreconditions,
                            FindDeviceOperationCapabilitiesUseCase findCapabilities) {
        this.findDevice = findDevice;
        this.startCharging = startCharging;
        this.stopCharging = stopCharging;
        this.operations = operations;
        this.operationPreconditions = operationPreconditions;
        this.findCapabilities = findCapabilities;
    }

    @GetMapping("/{deviceCode}")
    @Operation(summary = "Get a device", description = "Returns stable business status separately from live device and connector runtime state.")
    public ApiResponse<DeviceView> find(@PathVariable String deviceCode) {
        ChargingDevice device = findDevice.execute(deviceCode);
        DeviceOnlineStatus onlineStatus = operationPreconditions.onlineStatus(device);
        Instant lastSeenAt = operationPreconditions.runtimeState(device)
                .map(state -> state.lastSeenAt()).orElse(null);
        List<ConnectorRuntimeView> connectors = operationPreconditions.connectorRuntimeStates(device).stream()
                .map(ConnectorRuntimeView::from)
                .toList();
        DeviceOperationCapabilities capabilities = findCapabilities.execute(device.protocol());
        return ApiResponse.success(DeviceView.from(device, onlineStatus, lastSeenAt, connectors, capabilities));
    }

    @PostMapping("/{deviceCode}/commands/start-charging")
    @Operation(summary = "Request remote start", description = "Creates and dispatches a start command. A successful response means the command was created; charging may not have started yet. Poll GET /api/commands/{commandId} for its lifecycle.")
    public ApiResponse<CommandCreatedView> start(@PathVariable String deviceCode,
                                                  @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                                          description = "Charging start parameters",
                                                          required = true,
                                                          content = @io.swagger.v3.oas.annotations.media.Content(
                                                                  schema = @Schema(implementation = StartChargingRequest.class),
                                                                  examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                                          value = "{\"connectorNo\":\"01\",\"limitAmount\":50.00,\"logicalCardNo\":\"1234567890123456\",\"physicalCardNo\":\"1234567890123456\",\"businessRef\":\"fleet-2026-001\"}")))
                                                  @Valid @RequestBody StartChargingRequest request) {
        UUID commandId = startCharging.execute(deviceCode, request.connectorNo(), request.limitAmount(),
                request.logicalCardNo(), request.physicalCardNo(), request.businessRef());
        return ApiResponse.success(new CommandCreatedView(commandId));
    }

    @PostMapping("/{deviceCode}/commands/stop-charging")
    @Operation(summary = "Request remote stop", description = "Creates and dispatches a stop command. Device acceptance does not mean the charging transaction is finalized; poll the command and transaction resources.")
    public ApiResponse<CommandCreatedView> stop(@PathVariable String deviceCode,
                                                 @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                                         description = "Connector to stop",
                                                         required = true,
                                                         content = @io.swagger.v3.oas.annotations.media.Content(
                                                                 schema = @Schema(implementation = StopChargingRequest.class),
                                                                 examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                                         value = "{\"connectorNo\":\"01\"}")))
                                                 @Valid @RequestBody StopChargingRequest request) {
        UUID commandId = stopCharging.execute(deviceCode, request.connectorNo());
        return ApiResponse.success(new CommandCreatedView(commandId));
    }

    @PostMapping("/{deviceCode}/commands/time-sync")
    @Operation(summary = "Request device time synchronization", description = "Creates a time synchronization command; targetTime defaults to the server time when omitted.")
    public ApiResponse<CommandCreatedView> synchronizeTime(@PathVariable String deviceCode,
                                                            @RequestBody(required = false) TimeSyncRequest request) {
        Instant targetTime = request == null ? null : request.targetTime();
        return ApiResponse.success(new CommandCreatedView(operations.synchronizeTime(deviceCode, targetTime)));
    }

    @PostMapping("/{deviceCode}/commands/reset")
    @Operation(summary = "Request device reset", description = "Creates a device reset command. whenIdle defaults to false when the body is omitted.")
    public ApiResponse<CommandCreatedView> reset(@PathVariable String deviceCode,
                                                  @RequestBody(required = false) ResetDeviceRequest request) {
        return ApiResponse.success(new CommandCreatedView(operations.reset(deviceCode, request != null && request.whenIdle())));
    }

    @Schema(description = "Remote start request")
    public record StartChargingRequest(
            @NotBlank @Pattern(regexp = "\\d{1,2}") String connectorNo,
            @NotNull @DecimalMin("0.00") @DecimalMax("21474836.47") BigDecimal limitAmount,
            @Pattern(regexp = "\\d{1,16}") String logicalCardNo,
            @Pattern(regexp = "\\d{1,18}") String physicalCardNo,
            @Size(max = 128) String businessRef) {
    }

    @Schema(description = "Remote stop request")
    public record StopChargingRequest(@NotBlank @Pattern(regexp = "\\d{1,2}") String connectorNo) {
    }

    @Schema(description = "Optional target clock time; the server time is used when absent")
    public record TimeSyncRequest(Instant targetTime) {
    }

    @Schema(description = "Reset behavior")
    public record ResetDeviceRequest(boolean whenIdle) {
    }

    @Schema(description = "Identifier of the created asynchronous command")
    public record CommandCreatedView(UUID commandId) {
    }

    @Schema(description = "Platform device representation")
    public record DeviceView(UUID id, UUID tenantId, UUID stationId, String deviceCode,
                             String deviceName, String protocol, String protocolVersion,
                             DeviceBusinessStatus businessStatus, DeviceOnlineStatus onlineStatus,
                             Instant lastSeenAt, List<ConnectorRuntimeView> connectors,
                             DeviceOperationCapabilitiesView capabilities) {

        private static DeviceView from(ChargingDevice device, DeviceOnlineStatus onlineStatus,
                                       Instant lastSeenAt, List<ConnectorRuntimeView> connectors,
                                       DeviceOperationCapabilities capabilities) {
            return new DeviceView(device.id(), device.tenantId(), device.stationId(), device.deviceCode(),
                    device.deviceName(), device.protocol().name(), device.protocolVersion(), device.businessStatus(),
                    onlineStatus, lastSeenAt, connectors, DeviceOperationCapabilitiesView.from(capabilities));
        }
    }

    @Schema(description = "Operations implemented by the device protocol")
    public record DeviceOperationCapabilitiesView(boolean startCharging, boolean stopCharging,
                                                  boolean timeSync, boolean reset) {

        private static DeviceOperationCapabilitiesView from(DeviceOperationCapabilities capabilities) {
            return new DeviceOperationCapabilitiesView(capabilities.startCharging(), capabilities.stopCharging(),
                    capabilities.timeSync(), capabilities.reset());
        }
    }

    @Schema(description = "Latest reported runtime state for a connector")
    public record ConnectorRuntimeView(int connectorNo, ConnectorStatus runtimeStatus,
                                       Instant changedAt, Instant lastReportedAt) {

        private static ConnectorRuntimeView from(ConnectorRuntimeState state) {
            return new ConnectorRuntimeView(state.connectorNo(), state.status(),
                    state.changedAt(), state.lastReportedAt());
        }
    }
}
