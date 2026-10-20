package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.business.domain.transaction.ProtocolTransactionIdAllocator;
import com.voltbridge.device.model.DeviceTransactionStartRequest;
import com.voltbridge.device.model.DeviceTransactionStartResult;
import com.voltbridge.device.port.DeviceTransactionStartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处理任意协议设备主动确认交易开始的同步业务入口。
 *
 * <p>有远程启动交易时复用它；没有平台命令时创建本地交易，并通过持久化分配器产生
 * 数字协议流水。类中不包含任何具体接入协议、传输方式或厂商动作名称。</p>
 */
@Service
public class StartDeviceTransactionUseCase implements DeviceTransactionStartService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartDeviceTransactionUseCase.class);
    private final ChargingDeviceRepository deviceRepository;
    private final ConnectorRepository connectorRepository;
    private final ChargingTransactionRepository transactionRepository;
    private final ProtocolTransactionIdAllocator transactionIdAllocator;
    private final ChargingStartedService chargingStartedService;

    public StartDeviceTransactionUseCase(ChargingDeviceRepository deviceRepository,
                                         ConnectorRepository connectorRepository,
                                         ChargingTransactionRepository transactionRepository,
                                         ProtocolTransactionIdAllocator transactionIdAllocator,
                                         ChargingStartedService chargingStartedService) {
        this.deviceRepository = deviceRepository;
        this.connectorRepository = connectorRepository;
        this.transactionRepository = transactionRepository;
        this.transactionIdAllocator = transactionIdAllocator;
        this.chargingStartedService = chargingStartedService;
    }

    @Override
    @Transactional
    public synchronized DeviceTransactionStartResult start(DeviceTransactionStartRequest request) {
        ChargingDevice device = deviceRepository.findByDeviceCode(request.deviceId().value()).orElse(null);
        if (device == null || device.protocol() != request.protocol()) {
            return new DeviceTransactionStartResult(false, 0, null, "DEVICE_NOT_REGISTERED");
        }
        Connector connector = connectorRepository
                .findByDeviceIdAndConnectorNo(device.id(), request.connectorNo()).orElse(null);
        if (connector == null) {
            return new DeviceTransactionStartResult(false, 0, null, "CONNECTOR_NOT_FOUND");
        }

        ChargingTransaction transaction = transactionRepository
                .findActiveByDeviceAndConnector(device.id(), connector.id())
                .orElseGet(() -> ChargingTransaction.start(device.tenantId(), device.id(), device.deviceCode(),
                        connector.id(), connector.connectorNo(), request.protocol(),
                        request.startMethod(), request.occurredAt()));
        long protocolId = transaction.protocolTransactionId() == null
                ? transactionIdAllocator.nextId()
                : Long.parseLong(transaction.protocolTransactionId());
        transaction.begin(Long.toString(protocolId), request.startedAt(), request.startMeterKwh(),
                request.startMethod(), request.occurredAt());
        transactionRepository.save(transaction);
        chargingStartedService.completeStartCommand(transaction, request.occurredAt());
        LOGGER.info("Device transaction started: transactionId={}, protocolTransactionId={}, deviceCode={}, connectorNo={}, startMethod={}",
                transaction.id(), protocolId, device.deviceCode(), connector.connectorNo(), transaction.startMethod());
        return new DeviceTransactionStartResult(true, protocolId, transaction.id(), "ACCEPTED");
    }
}
