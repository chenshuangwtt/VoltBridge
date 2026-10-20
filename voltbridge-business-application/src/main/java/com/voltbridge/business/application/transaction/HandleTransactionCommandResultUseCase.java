package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.device.event.CommandResultEvent;
import com.voltbridge.device.event.CommandResultStatus;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.port.DeviceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将远程启动结果与充电交易关联。
 *
 * <p>协议接受结果只绑定协议交易号，交易仍保持 {@code STARTING}；明确拒绝时才将交易置为
 * {@code START_FAILED}。真实开始充电由标准状态、进度或开始事实确认。</p>
 */
@Service
public class HandleTransactionCommandResultUseCase implements DeviceEventHandler<CommandResultEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandleTransactionCommandResultUseCase.class);
    private final ChargingTransactionRepository repository;

    public HandleTransactionCommandResultUseCase(ChargingTransactionRepository repository) {
        this.repository = repository;
    }

    @Override public Class<CommandResultEvent> eventType() { return CommandResultEvent.class; }

    @Override
    public void handle(CommandResultEvent event) {
        repository.findByCommandId(event.commandId()).ifPresent(transaction -> {
            boolean changed = event.protocolTransactionId() != null
                    && transaction.bindProtocolTransactionId(event.protocolTransactionId(), event.occurredAt());
            if (event.status() == CommandResultStatus.FAILED) {
                changed |= transaction.markStartFailed(ChargingStopCategory.DEVICE_FAULT,
                        null, event.failureReason(), event.occurredAt());
            }
            if (changed) repository.save(transaction);
            LOGGER.info("Charging transaction correlated: transactionId={}, tradeNo={}, commandId={}, status={}",
                    transaction.id(), event.correlationId(), event.commandId(), transaction.status());
        });
    }
}
