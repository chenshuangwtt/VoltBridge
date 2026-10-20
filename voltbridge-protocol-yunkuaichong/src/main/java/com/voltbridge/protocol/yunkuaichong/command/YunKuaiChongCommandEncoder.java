package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.protocol.common.ProtocolContext;

public interface YunKuaiChongCommandEncoder<T extends DeviceCommand> {

    Class<T> commandType();

    byte[] encode(ProtocolContext context, T command);
}
