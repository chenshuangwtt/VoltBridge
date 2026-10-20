package com.voltbridge.business.application.transaction;

import com.voltbridge.device.event.ConnectorStatusEvent;
import com.voltbridge.device.model.ConnectorStatus;
import com.voltbridge.device.port.DeviceEventHandler;
import org.springframework.stereotype.Service;

/**
 * 使用连接器 {@code CHARGING} 状态作为充电真实开始的可信证据。
 */
@Service
public class HandleTransactionConnectorStatusUseCase implements DeviceEventHandler<ConnectorStatusEvent> {
    private final ChargingTransactionResolver resolver;
    private final ChargingStartedService chargingStartedService;

    public HandleTransactionConnectorStatusUseCase(ChargingTransactionResolver resolver,
                                                   ChargingStartedService chargingStartedService) {
        this.resolver = resolver;
        this.chargingStartedService = chargingStartedService;
    }

    @Override public Class<ConnectorStatusEvent> eventType() { return ConnectorStatusEvent.class; }

    @Override
    public void handle(ConnectorStatusEvent event) {
        if (event.status() == ConnectorStatus.CHARGING) {
            resolver.resolveActive(event.deviceId(), event.connectorNo())
                    .ifPresent(transaction -> chargingStartedService.confirm(transaction, null, event.occurredAt()));
        }
    }
}
