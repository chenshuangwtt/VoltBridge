package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.device.event.EnergyBreakdownItem;
import com.voltbridge.device.event.TransactionRecordEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import com.voltbridge.business.application.billing.ChargingTransactionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

/**
 * 接收协议无关的最终交易记录并完成充电交易持久化。
 *
 * <p>完整主线为：按 tradeNo 解析或恢复交易、应用权威最终字段、识别重复/冲突记录，
 * 最后在同一事务内保存。协议层只有在该同步处理成功返回后才会发送成功 ACK。</p>
 */
@Service
public class HandleTransactionRecordUseCase implements DeviceEventHandler<TransactionRecordEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandleTransactionRecordUseCase.class);
    private final ChargingTransactionResolver resolver;
    private final ChargingTransactionRepository repository;
    private final DeviceCommandRepository commandRepository;
    private final ApplicationEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Autowired
    public HandleTransactionRecordUseCase(ChargingTransactionResolver resolver,
                                          ChargingTransactionRepository repository,
                                          DeviceCommandRepository commandRepository,
                                          ApplicationEventPublisher eventPublisher) {
        this.resolver = resolver;
        this.repository = repository;
        this.commandRepository = commandRepository;
        this.eventPublisher = eventPublisher;
    }

    /** 保留既有纯协议测试夹具；生产构造器始终发布内部完成事件。 */
    public HandleTransactionRecordUseCase(ChargingTransactionResolver resolver,
                                          ChargingTransactionRepository repository,
                                          DeviceCommandRepository commandRepository) {
        this(resolver, repository, commandRepository, ignored -> { });
    }

    @Override public Class<TransactionRecordEvent> eventType() { return TransactionRecordEvent.class; }

    @Override
    @Transactional
    public void handle(TransactionRecordEvent event) {
        // 先恢复唯一领域交易，避免设备重传时创建第二条交易记录。
        ChargingTransaction transaction = resolveOrCreate(event);
        // 最终记录是起止时间、电表、电量和金额的权威来源。
        ChargingTransaction.FinalizationResult result = transaction.finalizeRecord(
                event.startedAt(), event.endedAt(), event.startMeterKwh(), event.endMeterKwh(),
                event.totalEnergyKwh(), event.totalAmount(), event.vehicleVin(), event.startMethod(),
                event.stopCategory(), event.rawStopCode(), event.rawStopDetail(), null,
                event.occurredAt(), rawFinalRecord(event), additionalInfo(event));
        // 首次记录落库；相同重传直接接受；冲突重传保留首份数据并留下告警。
        if (result == ChargingTransaction.FinalizationResult.APPLIED) {
            repository.save(transaction);
            LOGGER.info("Charging transaction completed: transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}, stopCategory={}",
                    transaction.id(), transaction.protocolTransactionId(), transaction.deviceCode(),
                    transaction.connectorNo(), transaction.stopCategory());
            // 只在最终记录首次被接受后发布业务内部完成事件；重复协议重传不会直接重复触发账单。
            eventPublisher.publishEvent(new ChargingTransactionCompletedEvent(transaction.id(), event.occurredAt()));
        } else if (result == ChargingTransaction.FinalizationResult.DUPLICATE) {
            LOGGER.info("Duplicate transaction record accepted: transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                    transaction.id(), transaction.protocolTransactionId(), transaction.deviceCode(), transaction.connectorNo());
        } else {
            LOGGER.warn("Conflicting duplicate transaction record ignored: transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                    transaction.id(), transaction.protocolTransactionId(), transaction.deviceCode(), transaction.connectorNo());
        }
        if (event.stopCategory().name().contains("FAULT") || event.stopCategory().name().contains("TIMEOUT")
                || event.stopCategory().name().contains("FAILURE")) {
            LOGGER.warn("Charging transaction completed with abnormal stop: transactionId={}, tradeNo={}, rawStopCode={}, stopCategory={}",
                    transaction.id(), transaction.protocolTransactionId(), event.rawStopCode(), event.stopCategory());
        }
        completeStopCommand(transaction, event);
    }

    private void completeStopCommand(ChargingTransaction transaction, TransactionRecordEvent event) {
        commandRepository.findActiveByTypeAndBusinessRef(
                        DeviceCommandType.STOP_CHARGING, transaction.id().toString())
                .ifPresent(command -> {
                    if (command.markSucceeded("{\"evidence\":\"final_transaction_record\",\"transactionId\":\""
                            + transaction.id() + "\"}", event.occurredAt())) {
                        commandRepository.save(command);
                        LOGGER.info("Stop command succeeded after final transaction record: commandId={}, transactionId={}, protocolTransactionId={}",
                                command.id(), transaction.id(), transaction.protocolTransactionId());
                    }
                });
    }

    private ChargingTransaction resolveOrCreate(TransactionRecordEvent event) {
        Optional<ChargingTransaction> resolved = resolver.resolve(event.deviceId(), event.protocol(),
                event.connectorNo(), event.protocolTransactionId(), event.occurredAt());
        if (resolved.isPresent()) return resolved.get();
        // 进程重启或历史本地启动可能没有活动内存状态，最终记录仍需具备自恢复能力。
        ChargingDevice device = resolver.requireDevice(event.deviceId());
        Connector connector = resolver.requireConnector(device, event.connectorNo());
        ChargingTransaction recovered = ChargingTransaction.start(device.tenantId(), device.id(),
                device.deviceCode(), connector.id(), connector.connectorNo(), event.protocol(),
                null, null, event.occurredAt());
        recovered.bindProtocolTransactionId(event.protocolTransactionId(), event.occurredAt());
        repository.save(recovered);
        LOGGER.info("Recovered transaction from final record: transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                recovered.id(), event.protocolTransactionId(), device.deviceCode(), connector.connectorNo());
        return recovered;
    }

    private String rawFinalRecord(TransactionRecordEvent event) {
        return "{\"frame\":\"" + event.rawRecord() + "\"}";
    }

    private String additionalInfo(TransactionRecordEvent event) {
        StringBuilder json = new StringBuilder("{\"energyBreakdown\":[");
        for (int index = 0; index < event.energyBreakdown().size(); index++) {
            EnergyBreakdownItem item = event.energyBreakdown().get(index);
            if (index > 0) json.append(',');
            json.append("{\"category\":\"").append(item.category()).append("\",\"energyKwh\":")
                    .append(item.energyKwh()).append(",\"amount\":").append(item.amount())
                    .append(",\"unitPrice\":").append(item.unitPrice()).append('}');
        }
        return json.append("],\"protocolDetail\":").append(event.additionalInfo()).append('}').toString();
    }
}
