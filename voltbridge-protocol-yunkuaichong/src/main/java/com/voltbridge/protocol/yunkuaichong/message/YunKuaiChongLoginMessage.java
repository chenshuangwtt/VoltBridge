package com.voltbridge.protocol.yunkuaichong.message;

public record YunKuaiChongLoginMessage(String deviceCode, int pileType, int connectorCount,
                                       int communicationProtocolVersion, String programVersion,
                                       int networkType, String simCard, int operatorCode) {
}
