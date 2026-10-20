package com.voltbridge.device.command;

import com.voltbridge.device.model.DeviceId;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台向任意充电设备下发命令时使用的统一模型。
 *
 * <p>业务层只依赖该模型，不感知云快充、OCPP、TCP 或 Netty；具体协议模块负责将命令编码为设备报文。</p>
 */
public sealed interface DeviceCommand permits StartChargingCommand, StopChargingCommand, SynchronizeTimeCommand, ResetDeviceCommand {

    UUID commandId();

    DeviceId deviceId();

    Instant createdAt();
}
