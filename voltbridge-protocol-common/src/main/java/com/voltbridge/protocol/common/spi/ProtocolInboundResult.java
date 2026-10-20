package com.voltbridge.protocol.common.spi;

import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.transport.api.TransportPayload;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一帧上行协议消息产生的统一事件和无需业务决策即可立即发送的回复。
 */
public record ProtocolInboundResult(List<DeviceEvent> events, List<TransportPayload> immediateReplies,
                                    Optional<ProtocolAuthenticationRequest> authenticationRequest,
                                    Optional<ProtocolTransactionStartRequest> transactionStartRequest) {

    public ProtocolInboundResult {
        events = List.copyOf(events);
        immediateReplies = List.copyOf(immediateReplies);
        authenticationRequest = Objects.requireNonNull(authenticationRequest);
        transactionStartRequest = Objects.requireNonNull(transactionStartRequest);
    }

    public ProtocolInboundResult(List<DeviceEvent> events, List<TransportPayload> immediateReplies,
                                 Optional<ProtocolAuthenticationRequest> authenticationRequest) {
        this(events, immediateReplies, authenticationRequest, Optional.empty());
    }

    public static ProtocolInboundResult empty() {
        return new ProtocolInboundResult(List.of(), List.of(), Optional.empty(), Optional.empty());
    }

    public static ProtocolInboundResult authentication(ProtocolAuthenticationRequest request) {
        return new ProtocolInboundResult(List.of(), List.of(), Optional.of(request), Optional.empty());
    }

    public static ProtocolInboundResult transactionStart(ProtocolTransactionStartRequest request) {
        return new ProtocolInboundResult(List.of(), List.of(), Optional.empty(), Optional.of(request));
    }

    public static ProtocolInboundResult events(List<DeviceEvent> events) {
        return new ProtocolInboundResult(events, List.of(), Optional.empty(), Optional.empty());
    }
}
