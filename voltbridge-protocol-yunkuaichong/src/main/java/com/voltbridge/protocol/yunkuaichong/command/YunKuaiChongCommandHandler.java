package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.protocol.common.spi.ProtocolInboundResult;
import com.voltbridge.protocol.yunkuaichong.message.YunKuaiChongFrame;

public interface YunKuaiChongCommandHandler {

    int command();

    ProtocolInboundResult handle(ProtocolContext context, YunKuaiChongFrame frame);
}
