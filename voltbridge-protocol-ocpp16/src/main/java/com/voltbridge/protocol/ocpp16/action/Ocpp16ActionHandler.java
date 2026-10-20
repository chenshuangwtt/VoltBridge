package com.voltbridge.protocol.ocpp16.action;

import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.ocpp16.message.Ocpp16Call;

/** 单个 OCPP 上行动作处理器。 */
public interface Ocpp16ActionHandler {

    String action();

    ProtocolInboundResult handle(ProtocolContext context, Ocpp16Call call);
}
