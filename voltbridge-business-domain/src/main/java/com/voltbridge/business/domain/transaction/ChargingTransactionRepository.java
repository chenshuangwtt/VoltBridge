package com.voltbridge.business.domain.transaction;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import com.voltbridge.device.model.DeviceProtocol;

/**
 * 充电交易聚合的领域仓储端口。
 */
public interface ChargingTransactionRepository {

    void save(ChargingTransaction transaction);

    Optional<ChargingTransaction> findById(UUID id);

    Optional<ChargingTransaction> findByCommandId(UUID commandId);

    /** 使用协议、设备和协议流水精确定位交易，是最终记录恢复和幂等判断的主查询。 */
    Optional<ChargingTransaction> findByProtocolTransactionId(DeviceProtocol protocol,
                                                              UUID deviceId,
                                                              String protocolTransactionId);

    /** 查询一个连接器唯一的非终态交易，用于尚未绑定 tradeNo 时的受控回退。 */
    Optional<ChargingTransaction> findActiveByDeviceAndConnector(UUID deviceId, UUID connectorId);

    List<ChargingTransaction> find(String deviceCode, Integer connectorNo,
                                   ChargingTransactionStatus status);
}
