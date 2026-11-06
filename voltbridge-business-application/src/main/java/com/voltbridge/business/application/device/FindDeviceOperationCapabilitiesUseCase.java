package com.voltbridge.business.application.device;

import com.voltbridge.device.model.DeviceOperationCapabilities;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.device.port.DeviceOperationCapabilityProvider;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves operation support from the registered protocol adapters. */
@Service
public class FindDeviceOperationCapabilitiesUseCase {

    private final Map<DeviceProtocol, DeviceOperationCapabilities> capabilitiesByProtocol;

    public FindDeviceOperationCapabilitiesUseCase(List<DeviceOperationCapabilityProvider> providers) {
        Map<DeviceProtocol, DeviceOperationCapabilities> registered = new EnumMap<>(DeviceProtocol.class);
        for (DeviceOperationCapabilityProvider provider : providers) {
            DeviceOperationCapabilities previous = registered.putIfAbsent(
                    provider.protocol(), provider.operationCapabilities());
            if (previous != null) {
                throw new IllegalStateException("Duplicate device operation capabilities for " + provider.protocol());
            }
        }
        capabilitiesByProtocol = Map.copyOf(registered);
    }

    public DeviceOperationCapabilities execute(DeviceProtocol protocol) {
        return capabilitiesByProtocol.getOrDefault(protocol, DeviceOperationCapabilities.NONE);
    }
}
