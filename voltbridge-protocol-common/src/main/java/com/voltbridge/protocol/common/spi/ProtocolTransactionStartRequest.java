package com.voltbridge.protocol.common.spi;

import com.voltbridge.device.model.DeviceTransactionStartRequest;

import java.util.Objects;

/**
 * 设备主动开始交易时，由协议层交给网关编排器的同步业务准入请求。
 */
public record ProtocolTransactionStartRequest(DeviceTransactionStartRequest request,
                                              ProtocolTransactionStartResponder responder) {

    public ProtocolTransactionStartRequest {
        Objects.requireNonNull(request, "transaction start request must not be null");
        Objects.requireNonNull(responder, "transaction start responder must not be null");
    }
}
