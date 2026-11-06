package com.voltbridge.device.port;

import com.voltbridge.device.model.DeviceOperationCapabilities;
import com.voltbridge.device.model.DeviceProtocol;

/** Declares the operations implemented by a protocol adapter. */
public interface DeviceOperationCapabilityProvider {

    DeviceProtocol protocol();

    DeviceOperationCapabilities operationCapabilities();
}
