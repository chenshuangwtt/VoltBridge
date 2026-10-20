package com.voltbridge.business.domain.transaction;

/**
 * 充电交易状态；与设备命令状态相互独立。
 */
public enum ChargingTransactionStatus {
    STARTING,
    CHARGING,
    ENDING,
    COMPLETED,
    START_FAILED,
    ABORTED
}
