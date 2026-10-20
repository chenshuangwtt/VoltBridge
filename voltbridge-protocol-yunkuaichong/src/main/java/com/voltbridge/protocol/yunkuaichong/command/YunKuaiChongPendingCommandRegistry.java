package com.voltbridge.protocol.yunkuaichong.command;

import com.voltbridge.common.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 保存云快充下行命令与上行结果之间的短期关联。
 *
 * <p>关联键统一包含命令类型、协议可用关联值、设备和枪号。远程启动使用交易流水；
 * 远程停充结果不回传序列号或流水，只能使用设备和枪号，并由业务层保证同一交易只有
 * 一个活动停充命令。该注册表只拥有线协议关联，不拥有设备路由或业务命令生命周期。</p>
 */
@Component
public class YunKuaiChongPendingCommandRegistry {

    private final ConcurrentHashMap<CorrelationKey, PendingCommand> commands = new ConcurrentHashMap<>();
    private final TimeProvider timeProvider;
    private final Duration retention;

    public YunKuaiChongPendingCommandRegistry(TimeProvider timeProvider,
                                              @Value("${voltbridge.protocol.yunkuaichong.pending-command-retention:PT10M}")
                                              Duration retention) {
        this.timeProvider = timeProvider;
        this.retention = retention;
    }

    public void register(String correlationId, String deviceCode, String connectorNo, UUID commandId) {
        register("START_CHARGING", correlationId, deviceCode, connectorNo, commandId);
    }

    public void register(String commandType, String correlationId, String deviceCode,
                         String connectorNo, UUID commandId) {
        cleanupExpired();
        CorrelationKey key = new CorrelationKey(commandType, correlationId, deviceCode, connectorNo);
        PendingCommand candidate = new PendingCommand(commandId, timeProvider.now().plus(retention));
        PendingCommand existing = commands.putIfAbsent(key, candidate);
        if (existing != null && !existing.commandId.equals(commandId)) {
            throw new IllegalStateException("YunKuaiChong correlation collision: " + correlationId);
        }
    }

    /**
     * 注册缺少唯一响应流水的命令，并以最新活动命令替换旧关联。
     * 该策略只用于业务层已控制并发的远程停充。
     */
    public void registerLatest(String commandType, String correlationId, String deviceCode,
                               String connectorNo, UUID commandId) {
        cleanupExpired();
        commands.put(new CorrelationKey(commandType, correlationId, deviceCode, connectorNo),
                new PendingCommand(commandId, timeProvider.now().plus(retention)));
    }

    public Optional<Resolution> resolve(String correlationId, String deviceCode, String connectorNo) {
        return resolve("START_CHARGING", correlationId, deviceCode, connectorNo);
    }

    public Optional<Resolution> resolve(String commandType, String correlationId,
                                        String deviceCode, String connectorNo) {
        cleanupExpired();
        PendingCommand command = commands.get(
                new CorrelationKey(commandType, correlationId, deviceCode, connectorNo));
        if (command == null) {
            return Optional.empty();
        }
        int count = command.resultCount.incrementAndGet();
        return Optional.of(new Resolution(command.commandId, count > 1));
    }

    int size() {
        cleanupExpired();
        return commands.size();
    }

    private void cleanupExpired() {
        Instant now = timeProvider.now();
        commands.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private record CorrelationKey(String commandType, String correlationId,
                                  String deviceCode, String connectorNo) {
    }

    private static final class PendingCommand {
        private final UUID commandId;
        private final Instant expiresAt;
        private final AtomicInteger resultCount = new AtomicInteger();

        private PendingCommand(UUID commandId, Instant expiresAt) {
            this.commandId = commandId;
            this.expiresAt = expiresAt;
        }
    }

    public record Resolution(UUID commandId, boolean duplicate) {
    }
}
