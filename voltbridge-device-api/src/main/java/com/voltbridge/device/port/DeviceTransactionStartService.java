package com.voltbridge.device.port;

import com.voltbridge.device.model.DeviceTransactionStartRequest;
import com.voltbridge.device.model.DeviceTransactionStartResult;

/** 协议设备主动开始交易时调用的同步、协议无关业务端口。 */
public interface DeviceTransactionStartService {

    DeviceTransactionStartResult start(DeviceTransactionStartRequest request);
}
