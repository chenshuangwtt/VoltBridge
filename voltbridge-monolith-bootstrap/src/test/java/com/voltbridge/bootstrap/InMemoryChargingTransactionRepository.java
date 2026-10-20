package com.voltbridge.bootstrap;

import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.device.model.DeviceProtocol;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryChargingTransactionRepository implements ChargingTransactionRepository {
    private final ConcurrentHashMap<UUID, ChargingTransaction> values = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ChargingTransaction transaction) {
        values.values().stream()
                .filter(item -> !item.id().equals(transaction.id()))
                .filter(item -> transaction.protocolTransactionId() != null)
                .filter(item -> item.protocol() == transaction.protocol())
                .filter(item -> item.deviceId().equals(transaction.deviceId()))
                .filter(item -> transaction.protocolTransactionId().equals(item.protocolTransactionId()))
                .findAny().ifPresent(item -> { throw new IllegalStateException("duplicate protocol transaction"); });
        values.put(transaction.id(), transaction);
    }

    @Override public Optional<ChargingTransaction> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
    @Override public Optional<ChargingTransaction> findByCommandId(UUID id) { return values.values().stream().filter(item -> id.equals(item.commandId())).findFirst(); }
    @Override public Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol, UUID deviceId, String tradeNo) { return values.values().stream().filter(item -> item.protocol() == protocol && item.deviceId().equals(deviceId) && tradeNo.equals(item.protocolTransactionId())).findFirst(); }
    @Override public Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId) { return values.values().stream().filter(ChargingTransaction::active).filter(item -> item.deviceId().equals(deviceId) && item.connectorId().equals(connectorId)).findFirst(); }
    @Override public List<ChargingTransaction> find(String deviceCode, Integer connectorNo, ChargingTransactionStatus status) { return values.values().stream().filter(item -> deviceCode == null || deviceCode.equals(item.deviceCode())).filter(item -> connectorNo == null || connectorNo == item.connectorNo()).filter(item -> status == null || status == item.status()).toList(); }
    int size() { return values.size(); }
}
