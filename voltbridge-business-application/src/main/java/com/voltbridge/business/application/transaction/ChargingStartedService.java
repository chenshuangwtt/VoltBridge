package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 统一处理“充电已真实开始”的业务确认。
 *
 * <p>该服务同时推进两个独立聚合：充电交易进入 {@code CHARGING}，原远程启动命令进入
 * {@code SUCCEEDED}。这样可以明确区分“设备已接受启动指令”和“车辆已经开始充电”。</p>
 */
@Service
public class ChargingStartedService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChargingStartedService.class);
    private final ChargingTransactionRepository transactionRepository;
    private final DeviceCommandRepository commandRepository;

    public ChargingStartedService(ChargingTransactionRepository transactionRepository,
                                  DeviceCommandRepository commandRepository) {
        this.transactionRepository = transactionRepository;
        this.commandRepository = commandRepository;
    }

    public void confirm(ChargingTransaction transaction, Integer startSoc, Instant occurredAt) {
        // 交易状态转换本身具备幂等性，重复状态/进度报文不会重复推进状态。
        if (transaction.markCharging(occurredAt, startSoc, occurredAt)) {
            transactionRepository.save(transaction);
            LOGGER.info("Charging transaction entered CHARGING: transactionId={}, tradeNo={}, deviceCode={}, connectorNo={}",
                    transaction.id(), transaction.protocolTransactionId(), transaction.deviceCode(), transaction.connectorNo());
        }
        // 启动命令只跟踪启动动作，确认真实充电后即可结束，不贯穿整个充电会话。
        if (transaction.commandId() != null) {
            completeStartCommand(transaction, occurredAt);
        }
    }

    /** 在交易已由其他同步用例保存后，仅完成关联的远程启动命令。 */
    public void completeStartCommand(ChargingTransaction transaction, Instant occurredAt) {
        if (transaction.commandId() != null) {
            commandRepository.findById(transaction.commandId()).ifPresent(command -> {
                if (command.markSucceeded("{\"evidence\":\"charging_started\"}", occurredAt)) {
                    commandRepository.save(command);
                    LOGGER.info("Start command succeeded after charging evidence: commandId={}, transactionId={}",
                            command.id(), transaction.id());
                }
            });
        }
    }
}
