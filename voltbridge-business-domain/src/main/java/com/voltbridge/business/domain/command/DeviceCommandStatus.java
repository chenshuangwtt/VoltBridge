package com.voltbridge.business.domain.command;

/**
 * 设备命令生命周期状态。SENT 仅表示写入连接成功，不能等同于设备执行成功。
 */
public enum DeviceCommandStatus {
    CREATED,
    SENT,
    ACKED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    TIMEOUT
}
