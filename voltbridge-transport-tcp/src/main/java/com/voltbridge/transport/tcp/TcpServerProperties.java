package com.voltbridge.transport.tcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "voltbridge.transport.tcp")
public class TcpServerProperties {

    private String endpointId = "yunkuaichong-v150-tcp";
    private String host = "0.0.0.0";
    private int port = 9000;
    private int maxConnections = 10_000;
    private Duration readerIdleTimeout = Duration.ofSeconds(120);
    private Framing framing = new Framing();

    public String getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(String endpointId) {
        this.endpointId = endpointId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Framing getFraming() {
        return framing;
    }

    public void setFraming(Framing framing) {
        this.framing = framing;
    }

    public Duration getReaderIdleTimeout() {
        return readerIdleTimeout;
    }

    public void setReaderIdleTimeout(Duration readerIdleTimeout) {
        this.readerIdleTimeout = readerIdleTimeout;
    }

    public enum ByteOrder {
        BIG_ENDIAN,
        LITTLE_ENDIAN
    }

    public static class Framing {

        private ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
        private int maxFrameLength = 8_192;
        private int lengthFieldOffset = 1;
        private int lengthFieldLength = 1;
        private int lengthAdjustment = 2;
        private int initialBytesToStrip;
        private boolean failFast = true;

        public ByteOrder getByteOrder() {
            return byteOrder;
        }

        public void setByteOrder(ByteOrder byteOrder) {
            this.byteOrder = byteOrder;
        }

        public int getMaxFrameLength() {
            return maxFrameLength;
        }

        public void setMaxFrameLength(int maxFrameLength) {
            this.maxFrameLength = maxFrameLength;
        }

        public int getLengthFieldOffset() {
            return lengthFieldOffset;
        }

        public void setLengthFieldOffset(int lengthFieldOffset) {
            this.lengthFieldOffset = lengthFieldOffset;
        }

        public int getLengthFieldLength() {
            return lengthFieldLength;
        }

        public void setLengthFieldLength(int lengthFieldLength) {
            this.lengthFieldLength = lengthFieldLength;
        }

        public int getLengthAdjustment() {
            return lengthAdjustment;
        }

        public void setLengthAdjustment(int lengthAdjustment) {
            this.lengthAdjustment = lengthAdjustment;
        }

        public int getInitialBytesToStrip() {
            return initialBytesToStrip;
        }

        public void setInitialBytesToStrip(int initialBytesToStrip) {
            this.initialBytesToStrip = initialBytesToStrip;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }
    }
}
