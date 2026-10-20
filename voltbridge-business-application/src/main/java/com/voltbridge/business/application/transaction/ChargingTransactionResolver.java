package com.voltbridge.business.application.transaction;

import com.voltbridge.business.domain.connector.Connector;
import com.voltbridge.business.domain.connector.ConnectorRepository;
import com.voltbridge.business.domain.device.ChargingDevice;
import com.voltbridge.business.domain.device.ChargingDeviceRepository;
import com.voltbridge.business.domain.transaction.ChargingTransaction;
import com.voltbridge.business.domain.transaction.ChargingTransactionRepository;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 充电交易关联服务，负责把设备上报的设备号、枪号和协议流水解析为领域交易。
 *
 * <p>主路径优先使用协议、设备主键和 tradeNo 精确匹配；仅当远程启动交易尚未绑定
 * tradeNo 时，才回退到该连接器唯一的活动交易并完成绑定。</p>
 */
@Service
public class ChargingTransactionResolver {

    private final ChargingDeviceRepository deviceRepository;
    private final ConnectorRepository connectorRepository;
    private final ChargingTransactionRepository transactionRepository;

    public ChargingTransactionResolver(ChargingDeviceRepository deviceRepository,
                                       ConnectorRepository connectorRepository,
                                       ChargingTransactionRepository transactionRepository) {
        this.deviceRepository = deviceRepository;
        this.connectorRepository = connectorRepository;
        this.transactionRepository = transactionRepository;
    }

    public Optional<ChargingTransaction> resolve(DeviceId deviceId, DeviceProtocol protocol,
                                                 int connectorNo, String tradeNo, Instant now) {
        ChargingDevice device = deviceRepository.findByDeviceCode(deviceId.value()).orElse(null);
        if (device == null) return Optional.empty();
        // 精确匹配可跨进程重启工作，是最终交易记录恢复和幂等处理的首选路径。
        Optional<ChargingTransaction> exact = transactionRepository.findByProtocolTransactionId(
                protocol, device.id(), tradeNo);
        if (exact.isPresent()) return exact;
        Connector connector = connectorRepository.findByDeviceIdAndConnectorNo(device.id(), connectorNo).orElse(null);
        if (connector == null) return Optional.empty();
        Optional<ChargingTransaction> active = transactionRepository.findActiveByDeviceAndConnector(
                device.id(), connector.id());
        // 只允许数据库约束保障下的唯一活动交易接收尚未绑定的协议流水。
        active.ifPresent(transaction -> {
            if (transaction.bindProtocolTransactionId(tradeNo, now)) {
                transactionRepository.save(transaction);
            }
        });
        return active;
    }

    public Optional<ChargingTransaction> resolveActive(DeviceId deviceId, int connectorNo) {
        // 连接器状态事件不携带 tradeNo，只能关联当前唯一活动交易。
        ChargingDevice device = deviceRepository.findByDeviceCode(deviceId.value()).orElse(null);
        if (device == null) return Optional.empty();
        return connectorRepository.findByDeviceIdAndConnectorNo(device.id(), connectorNo)
                .flatMap(connector -> transactionRepository.findActiveByDeviceAndConnector(device.id(), connector.id()));
    }

    public ChargingDevice requireDevice(DeviceId deviceId) {
        return deviceRepository.findByDeviceCode(deviceId.value())
                .orElseThrow(() -> new IllegalStateException("Charging device not found: " + deviceId.value()));
    }

    public Connector requireConnector(ChargingDevice device, int connectorNo) {
        return connectorRepository.findByDeviceIdAndConnectorNo(device.id(), connectorNo)
                .orElseThrow(() -> new IllegalStateException("Charging connector not found: "
                        + device.deviceCode() + "/" + connectorNo));
    }
}
