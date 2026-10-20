package com.voltbridge.protocol.ocpp16.correlation;

import com.voltbridge.common.time.TimeProvider;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.protocol.ocpp16.Ocpp16Properties;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理 OCPP 下行 CALL {@code uniqueId -> DeviceCommand.id} 的短期关联及清理。
 *
 * <p>它不保存 DeviceSession，也不管理 OCPP transactionId；前者属于网关路由，后者属于
 * ChargingTransaction 的协议交易关联。</p>
 */
@Component
public class Ocpp16PendingRequestRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ocpp16PendingRequestRegistry.class);

    private final ConcurrentHashMap<String, PendingRequest> requests = new ConcurrentHashMap<>();
    private final TimeProvider timeProvider;
    private final Ocpp16Properties properties;

    public Ocpp16PendingRequestRegistry(TimeProvider timeProvider, Ocpp16Properties properties) {
        this.timeProvider = timeProvider;
        this.properties = properties;
    }

    public void register(String uniqueId, UUID commandId, DeviceId deviceId,
                         String connectorNo, String commandType, String action) {
        cleanupExpired();
        PendingRequest request = new PendingRequest(commandId, deviceId, connectorNo,
                commandType, action, timeProvider.now().plus(properties.getRequestTimeout()));
        if (requests.putIfAbsent(uniqueId, request) != null) {
            throw new IllegalStateException("Duplicate OCPP uniqueId: " + uniqueId);
        }
    }

    public Optional<Resolution> resolve(String uniqueId) {
        cleanupExpired();
        PendingRequest request = requests.get(uniqueId);
        if (request == null) return Optional.empty();
        int count = request.responseCount.incrementAndGet();
        return Optional.of(new Resolution(request.commandId, request.deviceId, request.connectorNo,
                request.commandType, request.action, count > 1));
    }

    public int size() {
        cleanupExpired();
        return requests.size();
    }

    private void cleanupExpired() {
        Instant now = timeProvider.now();
        requests.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt.isAfter(now)) return false;
            LOGGER.warn("Expired OCPP pending request removed: uniqueId={}, commandId={}, action={}",
                    entry.getKey(), entry.getValue().commandId, entry.getValue().action);
            return true;
        });
    }

    private static final class PendingRequest {
        private final UUID commandId;
        private final DeviceId deviceId;
        private final String connectorNo;
        private final String commandType;
        private final String action;
        private final Instant expiresAt;
        private final AtomicInteger responseCount = new AtomicInteger();

        private PendingRequest(UUID commandId, DeviceId deviceId, String connectorNo,
                               String commandType, String action, Instant expiresAt) {
            this.commandId = commandId;
            this.deviceId = deviceId;
            this.connectorNo = connectorNo;
            this.commandType = commandType;
            this.action = action;
            this.expiresAt = expiresAt;
        }
    }

    public record Resolution(UUID commandId, DeviceId deviceId, String connectorNo,
                             String commandType, String action, boolean duplicate) {
    }
}
