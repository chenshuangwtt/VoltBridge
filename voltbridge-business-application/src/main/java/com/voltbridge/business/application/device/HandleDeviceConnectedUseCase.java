package com.voltbridge.business.application.device;

import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.device.event.DeviceConnectedEvent;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.model.DeviceConnectionDecision;
import com.voltbridge.device.port.DeviceConnectionAuthorizer;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HandleDeviceConnectedUseCase implements DeviceConnectionAuthorizer, DeviceEventHandler<DeviceConnectedEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandleDeviceConnectedUseCase.class);

    private final ChargingDeviceRepository deviceRepository;
    private final DeviceRuntimeStateStore runtimeStateStore;

    public HandleDeviceConnectedUseCase(ChargingDeviceRepository deviceRepository,
                                        DeviceRuntimeStateStore runtimeStateStore) {
        this.deviceRepository = deviceRepository;
        this.runtimeStateStore = runtimeStateStore;
    }

    @Override
    public DeviceConnectionDecision authorize(DeviceConnectedEvent event) {
        ChargingDevice device = deviceRepository.findByDeviceCode(event.deviceId().value()).orElse(null);
        if (device == null) {
            LOGGER.warn("Device login rejected: deviceCode={}, reason=not_registered", event.deviceId().value());
            return DeviceConnectionDecision.reject("device_not_registered");
        }
        if ("DISABLED".equals(device.status())) {
            LOGGER.warn("Device login rejected: deviceCode={}, reason=disabled", event.deviceId().value());
            return DeviceConnectionDecision.reject("device_disabled");
        }
        if (device.protocol() != event.protocol()) {
            LOGGER.warn("Device login rejected: deviceCode={}, expectedProtocol={}, actualProtocol={}",
                    event.deviceId().value(), device.protocol(), event.protocol());
            return DeviceConnectionDecision.reject("protocol_mismatch");
        }
        LOGGER.info("Device login validated: deviceCode={}, sessionId={}",
                event.deviceId().value(), event.sessionId());
        return DeviceConnectionDecision.accept();
    }

    @Override
    public Class<DeviceConnectedEvent> eventType() {
        return DeviceConnectedEvent.class;
    }

    @Override
    public void handle(DeviceConnectedEvent event) {
        runtimeStateStore.markOnline(event.deviceId(), event.sessionId(), event.occurredAt());
        LOGGER.info("Device runtime state changed: deviceCode={}, sessionId={}, onlineStatus=ONLINE",
                event.deviceId().value(), event.sessionId());
    }
}
