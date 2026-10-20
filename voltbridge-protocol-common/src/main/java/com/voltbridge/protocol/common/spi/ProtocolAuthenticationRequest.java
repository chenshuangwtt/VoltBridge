package com.voltbridge.protocol.common.spi;

import com.voltbridge.device.event.DeviceConnectedEvent;

import java.util.Objects;

/**
 * 协议登录解析后提交给网关和业务应用的认证请求。
 */
public record ProtocolAuthenticationRequest(DeviceConnectedEvent event,
                                            ProtocolAuthenticationResponder responder) {

    public ProtocolAuthenticationRequest {
        Objects.requireNonNull(event, "connected event must not be null");
        Objects.requireNonNull(responder, "authentication responder must not be null");
    }
}
