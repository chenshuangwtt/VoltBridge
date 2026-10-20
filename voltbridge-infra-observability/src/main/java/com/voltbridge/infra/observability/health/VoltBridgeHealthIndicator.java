package com.voltbridge.infra.observability.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import com.voltbridge.transport.api.TransportServer;

import java.util.List;

@Component
public class VoltBridgeHealthIndicator implements HealthIndicator {

    private final List<TransportServer> transportServers;

    public VoltBridgeHealthIndicator(List<TransportServer> transportServers) {
        this.transportServers = List.copyOf(transportServers);
    }

    @Override
    public Health health() {
        long runningServers = transportServers.stream().filter(TransportServer::isRunning).count();
        Health.Builder builder = runningServers == transportServers.size() ? Health.up() : Health.down();
        return builder.withDetail("application", "voltbridge")
                .withDetail("transportServers", transportServers.size())
                .withDetail("runningTransportServers", runningServers)
                .build();
    }
}
