package com.voltbridge.device.model;

/** 跨协议统一的充电启动方式。 */
public enum ChargingStartMethod {
    REMOTE,
    CARD,
    OFFLINE_CARD,
    VIN,
    LOCAL,
    UNKNOWN
}
