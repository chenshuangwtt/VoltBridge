package com.voltbridge.device.port;

import com.voltbridge.device.command.DeviceCommand;

import java.util.concurrent.CompletionStage;

/**
 * 业务应用向在线设备发送统一命令的出站端口，由设备网关层实现。
 *
 * <p>业务层只能依赖该接口和标准 {@link DeviceCommand}，不能选择具体协议或持有设备会话。
 * 返回成功只表示传输层完成写出，不代表设备 ACK，更不代表真实充电动作已经发生；后续状态
 * 必须由标准设备事件驱动。</p>
 */
public interface DeviceCommandGateway {

    CompletionStage<Void> dispatch(DeviceCommand command);
}
