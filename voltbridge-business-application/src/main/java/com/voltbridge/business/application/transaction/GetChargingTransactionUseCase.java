package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ChargingTransactionStatus;
import com.voltbridge.common.exception.VoltBridgeException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 充电交易查询用例，为 Web 层提供按主键和常用条件查询的统一入口。
 */
@Service
public class GetChargingTransactionUseCase {
    private final ChargingTransactionRepository repository;

    public GetChargingTransactionUseCase(ChargingTransactionRepository repository) {
        this.repository = repository;
    }

    public ChargingTransaction find(UUID id) {
        return repository.findById(id).orElseThrow(() ->
                new VoltBridgeException("TRANSACTION_NOT_FOUND", "Charging transaction not found: " + id));
    }

    public List<ChargingTransaction> search(String deviceCode, Integer connectorNo,
                                            ChargingTransactionStatus status) {
        return repository.find(deviceCode, connectorNo, status);
    }
}
