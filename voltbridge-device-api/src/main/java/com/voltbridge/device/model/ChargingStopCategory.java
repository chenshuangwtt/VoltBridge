package com.voltbridge.device.model;

/**
 * 跨协议统一的停止原因分类；具体协议原始码另行保留用于诊断。
 */
public enum ChargingStopCategory {
    USER_STOP,
    REMOTE_STOP,
    TARGET_SOC_REACHED,
    ENERGY_LIMIT_REACHED,
    AMOUNT_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    VEHICLE_STOP,
    DEVICE_FAULT,
    BMS_FAULT,
    COMMUNICATION_TIMEOUT,
    POWER_FAILURE,
    EMERGENCY_STOP,
    OTHER,
    UNKNOWN
}
