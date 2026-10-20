package com.voltbridge.transport.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** OCPP WebSocket 监听器配置，不包含任何 OCPP 消息语义。 */
@Component
@ConfigurationProperties(prefix = "voltbridge.transport.websocket")
public class WebSocketServerProperties {

    private boolean enabled = true;
    private String endpointId = "ocpp16-websocket";
    private String host = "0.0.0.0";
    private int port = 9100;
    private String path = "/ocpp";
    private int maxContentLength = 1_048_576;
    private Duration idleTimeout = Duration.ofMinutes(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getMaxContentLength() { return maxContentLength; }
    public void setMaxContentLength(int maxContentLength) { this.maxContentLength = maxContentLength; }
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
}
