package com.voltbridge.business.application.command;

import com.voltbridge.business.domain.command.DeviceCommand;
import com.voltbridge.business.domain.command.DeviceCommandRepository;
import com.voltbridge.common.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 扫描已经发送但长期未收到协议响应的命令，并将其终止为超时。
 *
 * <p>这里只处理 {@code SENT} 命令，避免把尚未写入网络的命令误判为设备超时；
 * 超时是平台侧终态，迟到的设备响应会由命令聚合的状态机拒绝。</p>
 */
@Service
public class CommandTimeoutService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandTimeoutService.class);

    private final DeviceCommandRepository commandRepository;
    private final TimeProvider timeProvider;

    public CommandTimeoutService(DeviceCommandRepository commandRepository, TimeProvider timeProvider) {
        this.commandRepository = commandRepository;
        this.timeProvider = timeProvider;
    }

    @Scheduled(fixedDelayString = "${voltbridge.device.command.timeout-scan-interval:PT1S}")
    public int checkExpiredCommands() {
        int timedOut = 0;
        // 仓储负责筛出到期的 SENT 命令，聚合负责最终判断状态转换是否合法。
        for (DeviceCommand command : commandRepository.findSentExpiredBefore(timeProvider.now())) {
            if (command.markTimedOut(timeProvider.now())) {
                commandRepository.save(command);
                timedOut++;
                LOGGER.warn("DeviceCommand timeout: commandId={}, deviceCode={}, connectorNo={}",
                        command.id(), command.deviceCode(), command.connectorNo());
            }
        }
        return timedOut;
    }
}
