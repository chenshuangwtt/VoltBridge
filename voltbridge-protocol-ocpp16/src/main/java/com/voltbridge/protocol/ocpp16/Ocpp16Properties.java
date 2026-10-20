package com.voltbridge.protocol.ocpp16;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** OCPP 1.6 协议行为配置。 */
@Component
@ConfigurationProperties(prefix = "voltbridge.protocol.ocpp16")
public class Ocpp16Properties {

    private boolean enabled = true;
    private Duration heartbeatInterval = Duration.ofSeconds(60);
    private Duration requestTimeout = Duration.ofSeconds(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
