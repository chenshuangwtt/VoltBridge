package com.voltbridge.business.application.device;

import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.DeviceBusinessStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceOnlineStatus;
import com.voltbridge.device.port.ConnectorRuntimeStateStore;
import com.voltbridge.device.port.DeviceRuntimeStateStore;
import com.voltbridge.device.runtime.ConnectorRuntimeState;
import com.voltbridge.device.runtime.DeviceRuntimeState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Shared operational checks over the existing business and runtime state stores. */
@Service
public class DeviceOperationPreconditions {

    private final DeviceRuntimeStateStore deviceRuntimeStates;
    private final ConnectorRuntimeStateStore connectorRuntimeStates;

    public DeviceOperationPreconditions(DeviceRuntimeStateStore deviceRuntimeStates,
                                        ConnectorRuntimeStateStore connectorRuntimeStates) {
        this.deviceRuntimeStates = deviceRuntimeStates;
        this.connectorRuntimeStates = connectorRuntimeStates;
    }

    public void requireDeviceEnabled(ChargingDevice device) {
        if (device.businessStatus() == DeviceBusinessStatus.DISABLED) {
            throw new VoltBridgeException("DEVICE_DISABLED", "Device is disabled: " + device.deviceCode());
        }
    }

    public void requireOnline(ChargingDevice device) {
        if (onlineStatus(device) != DeviceOnlineStatus.ONLINE) {
            throw new VoltBridgeException("DEVICE_OFFLINE", "Device is not online: " + device.deviceCode());
        }
    }

    public void requireConnectorBusinessAvailable(Connector connector) {
        String status = connector.status();
        if (!"AVAILABLE".equalsIgnoreCase(status)) {
            throw connectorUnavailable(connector);
        }
    }

    public void requireConnectorRuntimeAvailable(ChargingDevice device, int connectorNo) {
        Optional<ConnectorRuntimeState> runtime = connectorRuntimeStates.findConnector(
                new DeviceId(device.deviceCode()), connectorNo);
        if (runtime.isEmpty()) {
            // Some protocols do not report a connector state during login. The registered connector's
            // business status remains the fallback until the first runtime report arrives.
            return;
        }
        ConnectorStatus status = runtime.orElseThrow().status();
        if (status != ConnectorStatus.AVAILABLE && status != ConnectorStatus.PLUGGED) {
            throw new VoltBridgeException("CONNECTOR_NOT_AVAILABLE",
                    "Connector is not available: " + device.deviceCode() + "/" + connectorNo);
        }
    }

    public DeviceOnlineStatus onlineStatus(ChargingDevice device) {
        return runtimeState(device).map(DeviceRuntimeState::onlineStatus).orElse(DeviceOnlineStatus.UNKNOWN);
    }

    public Optional<DeviceRuntimeState> runtimeState(ChargingDevice device) {
        return deviceRuntimeStates.find(new DeviceId(device.deviceCode()));
    }

    public List<ConnectorRuntimeState> connectorRuntimeStates(ChargingDevice device) {
        return connectorRuntimeStates.findConnectors(new DeviceId(device.deviceCode()));
    }

    private VoltBridgeException connectorUnavailable(Connector connector) {
        return new VoltBridgeException("CONNECTOR_NOT_AVAILABLE",
                "Connector is not available: " + connector.deviceId() + "/" + connector.connectorNo());
    }
}
