package com.voltbridge.business.application.device;

import com.voltbridge.device.event.DeviceDiagnosticEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 诊断事件的最小业务订阅者。
 *
 * <p>本阶段不引入新的诊断持久化模型；通过结构化日志保留设备、交易与分类信息，供日志采集和
 * 告警系统消费，同时确保单体与 Kafka 两条事件路径均有安全的业务落点。</p>
 */
@Service
public class HandleDeviceDiagnosticUseCase implements DeviceEventHandler<DeviceDiagnosticEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandleDeviceDiagnosticUseCase.class);
    @Override public Class<DeviceDiagnosticEvent> eventType() { return DeviceDiagnosticEvent.class; }
    @Override public void handle(DeviceDiagnosticEvent event) {
        LOGGER.warn("Device diagnostic received: eventId={}, deviceCode={}, connectorNo={}, severity={}, category={}, code={}, transactionId={}",
                event.eventId(), event.deviceId().value(), event.connectorNo(), event.severity(), event.category(),
                event.code(), event.protocolTransactionId());
    }
}
