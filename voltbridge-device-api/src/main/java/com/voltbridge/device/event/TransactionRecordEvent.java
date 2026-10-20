package com.voltbridge.device.event;

import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.model.DeviceProtocol;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 协议无关的最终交易记录事件。
 *
 * <p>承载平台可复用的计量、金额、车辆及停止信息；协议专属细节放入 additionalInfo，
 * 避免业务应用直接依赖具体协议 DTO。</p>
 */
public record TransactionRecordEvent(UUID eventId, DeviceId deviceId, DeviceProtocol protocol,
                                     int connectorNo, String protocolTransactionId,
                                     Instant startedAt, Instant endedAt,
                                     BigDecimal startMeterKwh, BigDecimal endMeterKwh,
                                     BigDecimal totalEnergyKwh, BigDecimal totalAmount,
                                     String vehicleVin, ChargingStartMethod startMethod,
                                     ChargingStopCategory stopCategory, int rawStopCode,
                                     String rawStopDetail, Instant transactionTime,
                                     List<EnergyBreakdownItem> energyBreakdown,
                                     String rawRecord, String additionalInfo,
                                     Instant occurredAt) implements DeviceEvent {

    public TransactionRecordEvent {
        // 防止发布后明细集合被调用方修改，保证事件值语义。
        energyBreakdown = List.copyOf(energyBreakdown);
    }
}
