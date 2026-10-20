package com.voltbridge.business.domain.transaction;

/** 为需要平台分配数字协议流水的设备交易提供持久化序号。 */
public interface ProtocolTransactionIdAllocator {

    long nextId();
}
