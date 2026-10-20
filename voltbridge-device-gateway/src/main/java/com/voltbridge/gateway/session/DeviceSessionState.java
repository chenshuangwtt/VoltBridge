package com.voltbridge.gateway.session;

/**
 * CONNECTED：底层连接已经建立，但还不知道这是谁。比如 TCP socket 已经连上，OCPP WebSocket 也已经建立了，但设备还没完成登录/Boot。
 * IDENTIFYING：正在识别设备身份。比如云快充收到 0x01 Login，从报文里解析 deviceCode；OCPP 从 WebSocket path 或 BootNotification 确认设备身份。
 * AUTHENTICATING：设备身份已经知道，但还在判断是否允许它进入系统。比如查设备是否存在、是否属于有效租户、是否被禁用等。
 * ACTIVE：身份确认 + 认证通过，可以正常接收心跳、状态、充电数据，也可以接收平台下行命令。
 * CLOSING：正在关闭，属于中间状态。比如主动踢旧连接、设备重连替换旧 session、服务 shutdown。
 * CLOSED：彻底结束，这个 session 不能再使用。
 */
public enum DeviceSessionState {
    CONNECTED,
    IDENTIFYING,
    AUTHENTICATING,
    ACTIVE,
    CLOSING,
    CLOSED
}
