package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.command.DeviceCommandType;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.device.event.ChargingEndedEvent;
import com.voltbridge.device.port.DeviceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 处理协议层产生的充电结束事件。
 *
 * <p>结束通知只把交易推进到 {@code ENDING} 并保存结束侧数据；最终电量和金额仍以
 * 后续交易记录为准，因此这里不会提前标记 {@code COMPLETED}。</p>
 */
@Service
public class HandleChargingEndedUseCase implements DeviceEventHandler<ChargingEndedEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandleChargingEndedUseCase.class);
    private final ChargingTransactionResolver resolver;
    private final ChargingTransactionRepository repository;
    private final DeviceCommandRepository commandRepository;

    public HandleChargingEndedUseCase(ChargingTransactionResolver resolver,
                                      ChargingTransactionRepository repository,
                                      DeviceCommandRepository commandRepository) {
        this.resolver = resolver;
        this.repository = repository;
        this.commandRepository = commandRepository;
    }

    @Override public Class<ChargingEndedEvent> eventType() { return ChargingEndedEvent.class; }

    @Override
    public void handle(ChargingEndedEvent event) {
        // 使用 tradeNo 精确关联；必要时由解析器绑定尚未绑定流水的唯一活动交易。
        resolver.resolve(event.deviceId(), event.protocol(), event.connectorNo(),
                        event.protocolTransactionId(), event.occurredAt())
                .ifPresentOrElse(transaction -> {
                    if (transaction.markEnding(event.occurredAt(), event.endSoc(),
                            event.chargingDuration(), event.outputEnergyKwh(),
                            event.additionalInfo(), event.occurredAt())) {
                        repository.save(transaction);
                        LOGGER.info("Charging transaction entered ENDING: transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                                transaction.id(), transaction.protocolTransactionId(),
                                transaction.deviceCode(), transaction.connectorNo());
                        // 标准结束事件是停充业务效果的真实证据，只完成同一交易上已确认的停充命令。
                        commandRepository.findActiveByTypeAndBusinessRef(
                                        DeviceCommandType.STOP_CHARGING, transaction.id().toString())
                                .ifPresent(command -> {
                                    String response = "{\"evidence\":\"CHARGING_ENDED\",\"transactionId\":\""
                                            + transaction.id() + "\",\"tradeNo\":\""
                                            + transaction.protocolTransactionId() + "\"}";
                                    if (command.markSucceeded(response, event.occurredAt())) {
                                        commandRepository.save(command);
                                        LOGGER.info("Stop command succeeded after charging ended: commandId={}, transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                                                command.id(), transaction.id(), transaction.protocolTransactionId(),
                                                transaction.deviceCode(), transaction.connectorNo());
                                    }
                                });
                    }
                }, () -> LOGGER.warn("End charge has no matching transaction: tradeNo={}, deviceCode={}, connectorNo={}",
                        event.protocolTransactionId(), event.deviceId().value(), event.connectorNo()));
    }
}
