package com.voltbridge.transport.api;

/**
 * 传输连接生命周期监听器，用于把连接和已完成拆分的传输帧交给上层网关。
 */
public interface TransportConnectionListener {

    default void onConnected(TransportSession session) {
    }

    default void onFrame(TransportSession session, TransportFrame frame) {
    }

    default void onDisconnected(TransportSession session, Throwable cause) {
    }
}
