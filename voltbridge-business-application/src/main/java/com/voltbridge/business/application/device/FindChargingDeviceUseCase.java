package com.voltbridge.business.application.device;

import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import org.springframework.stereotype.Service;

@Service
public class FindChargingDeviceUseCase {

    private final ChargingDeviceRepository deviceRepository;

    public FindChargingDeviceUseCase(ChargingDeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public ChargingDevice execute(String deviceCode) {
        return deviceRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new VoltBridgeException("DEVICE_NOT_FOUND", "Device not found: " + deviceCode));
    }
}
