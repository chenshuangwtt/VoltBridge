package com.voltbridge.protocol.common.spi;

import com.voltbridge.device.command.DeviceCommand;
import com.voltbridge.device.model.DeviceProtocol;
import com.voltbridge.protocol.common.ProtocolContext;
import com.voltbridge.transport.api.TransportPayload;

/**
 * 设备网关调用的协议插件边界，负责线协议与平台统一设备模型之间的双向转换。
 *
 * <p>入站方向把 {@link TransportPayload} 转为标准事件、认证请求或协议即时回复；出站方向把
 * 标准 {@link DeviceCommand} 编码为传输载荷。实现可依赖协议 codec，但不得依赖业务用例、
 * 持久化实现或 Netty 运行时对象。</p>
 */
public interface ProtocolAdapter {

    String endpointId();

    DeviceProtocol protocol();

    ProtocolInboundResult decode(ProtocolContext context, TransportPayload frame);

    TransportPayload encode(ProtocolContext context, DeviceCommand command);
}
