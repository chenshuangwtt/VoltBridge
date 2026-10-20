package com.voltbridge.infra.observability.metrics;

import com.voltbridge.device.port.DeviceRuntimeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeviceMetrics implements DeviceRuntimeMetrics {

    private final AtomicLong connectedDevices = new AtomicLong();
    private final Counter heartbeatEvents;
    private final Counter offlineTimeouts;
    private final Counter connectorStatusChanges;
    private final Counter chargingProgressEvents;

    public DeviceMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("voltbridge.device.connected", connectedDevices, AtomicLong::get)
                .description("Current number of online devices")
                .register(meterRegistry);
        heartbeatEvents = meterRegistry.counter("voltbridge.device.heartbeat.events");
        offlineTimeouts = meterRegistry.counter("voltbridge.device.offline.timeouts");
        connectorStatusChanges = meterRegistry.counter("voltbridge.connector.status.changes");
        chargingProgressEvents = meterRegistry.counter("voltbridge.charging.progress.events");
    }

    @Override
    public void connectedDevices(long count) {
        connectedDevices.set(count);
    }

    @Override
    public void heartbeatReceived() {
        heartbeatEvents.increment();
    }

    @Override
    public void offlineTimeout() {
        offlineTimeouts.increment();
    }

    @Override
    public void connectorStatusChanged() {
        connectorStatusChanges.increment();
    }

    @Override
    public void chargingProgressReceived() {
        chargingProgressEvents.increment();
    }
}
