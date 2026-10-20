package com.voltbridge.protocol.yunkuaichong.message;

public record YunKuaiChongHeartbeatMessage(String deviceCode, int connectorNo, int rawStatus) {
}
