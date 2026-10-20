package com.voltbridge.protocol.common.spi;

import com.voltbridge.device.model.DeviceTransactionStartResult;
import com.voltbridge.transport.api.TransportPayload;

/**
 * 将业务层对设备主动开充的决定编码回当前协议连接。
 *
 * <p>协议处理器只负责保存当前帧的序号和加密标记；网关在异步取得业务决定后调用该接口，
 * 从而避免在 Netty I/O 线程中执行数据库或 gRPC 调用。</p>
 */
@FunctionalInterface
public interface ProtocolTransactionStartResponder {

    TransportPayload encode(DeviceTransactionStartResult result);
}
