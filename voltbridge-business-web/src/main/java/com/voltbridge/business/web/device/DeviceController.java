package com.voltbridge.business.web.device;

import com.voltbridge.business.application.charging.StartChargingUseCase;
import com.voltbridge.business.application.charging.StopChargingUseCase;
import com.voltbridge.business.application.device.FindChargingDeviceUseCase;
import com.voltbridge.business.application.device.DeviceOperationCommandUseCase;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.common.result.ApiResponse;
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
import java.util.UUID;

/** 设备查询及远程启停命令的 REST 输入适配器，不承载业务规则。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final FindChargingDeviceUseCase findDevice;
    private final StartChargingUseCase startCharging;
    private final StopChargingUseCase stopCharging;
    private final DeviceOperationCommandUseCase operations;

    @org.springframework.beans.factory.annotation.Autowired
    public DeviceController(FindChargingDeviceUseCase findDevice, StartChargingUseCase startCharging,
                            StopChargingUseCase stopCharging, DeviceOperationCommandUseCase operations) {
        this.findDevice = findDevice;
        this.startCharging = startCharging;
        this.stopCharging = stopCharging;
        this.operations = operations;
    }

    /** 保留现有 API 集成测试的构造方式；生产控制器使用完整依赖构造器。 */
    public DeviceController(FindChargingDeviceUseCase findDevice, StartChargingUseCase startCharging,
                            StopChargingUseCase stopCharging) {
        this(findDevice, startCharging, stopCharging, null);
    }

    @GetMapping("/{deviceCode}")
    public ApiResponse<DeviceView> find(@PathVariable String deviceCode) {
        return ApiResponse.success(DeviceView.from(findDevice.execute(deviceCode)));
    }

    @PostMapping("/{deviceCode}/commands/start-charging")
    public ApiResponse<CommandCreatedView> start(@PathVariable String deviceCode,
                                                  @Valid @RequestBody StartChargingRequest request) {
        UUID commandId = startCharging.execute(deviceCode, request.connectorNo(), request.limitAmount(),
                request.logicalCardNo(), request.physicalCardNo(), request.businessRef());
        return ApiResponse.success(new CommandCreatedView(commandId));
    }

    @PostMapping("/{deviceCode}/commands/stop-charging")
    public ApiResponse<CommandCreatedView> stop(@PathVariable String deviceCode,
                                                 @Valid @RequestBody StopChargingRequest request) {
        UUID commandId = stopCharging.execute(deviceCode, request.connectorNo());
        return ApiResponse.success(new CommandCreatedView(commandId));
    }

    @PostMapping("/{deviceCode}/commands/time-sync")
    public ApiResponse<CommandCreatedView> synchronizeTime(@PathVariable String deviceCode,
                                                            @RequestBody(required = false) TimeSyncRequest request) {
        Instant targetTime = request == null ? null : request.targetTime();
        return ApiResponse.success(new CommandCreatedView(operations.synchronizeTime(deviceCode, targetTime)));
    }

    @PostMapping("/{deviceCode}/commands/reset")
    public ApiResponse<CommandCreatedView> reset(@PathVariable String deviceCode,
                                                  @RequestBody(required = false) ResetDeviceRequest request) {
        return ApiResponse.success(new CommandCreatedView(operations.reset(deviceCode, request != null && request.whenIdle())));
    }

    public record StartChargingRequest(
            @NotBlank @Pattern(regexp = "\\d{1,2}") String connectorNo,
            @NotNull @DecimalMin("0.00") @DecimalMax("21474836.47") BigDecimal limitAmount,
            @Pattern(regexp = "\\d{1,16}") String logicalCardNo,
            @Pattern(regexp = "\\d{1,18}") String physicalCardNo,
            @Size(max = 128) String businessRef) {
    }

    public record StopChargingRequest(@NotBlank @Pattern(regexp = "\\d{1,2}") String connectorNo) {
    }

    public record TimeSyncRequest(Instant targetTime) {
    }

    public record ResetDeviceRequest(boolean whenIdle) {
    }

    public record CommandCreatedView(UUID commandId) {
    }

    public record DeviceView(UUID id, UUID tenantId, UUID stationId, String deviceCode,
                             String deviceName, String protocol, String protocolVersion, String status) {

        private static DeviceView from(ChargingDevice device) {
            return new DeviceView(device.id(), device.tenantId(), device.stationId(), device.deviceCode(),
                    device.deviceName(), device.protocol().name(), device.protocolVersion(), device.status());
        }
    }
}
