package com.voltbridge.protocol.yunkuaichong.message;

import com.voltbridge.device.event.EnergyBreakdownItem;
import com.voltbridge.device.model.ChargingStartMethod;
import com.voltbridge.device.model.ChargingStopCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 云快充 V1.5 0x3B 的协议内解析结果。
 *
 * <p>已完成字节序、BCD、时间及比例转换，后续由命令处理器映射为平台标准事件。</p>
 */
public record YunKuaiChongTransactionRecordMessage(String transactionId, String deviceCode,
                                                   int connectorNo, Instant startedAt, Instant endedAt,
                                                   BigDecimal startMeterKwh, BigDecimal endMeterKwh,
                                                   BigDecimal totalEnergyKwh, BigDecimal totalAmount,
                                                   String vehicleVin, ChargingStartMethod startMethod,
                                                   int rawStartMethod, ChargingStopCategory stopCategory,
                                                   int rawStopCode, String rawStopDetail,
                                                   Instant transactionTime,
                                                   List<EnergyBreakdownItem> energyBreakdown,
                                                   String additionalInfo) {
    public YunKuaiChongTransactionRecordMessage {
        // 保持协议消息不可变，避免事件映射过程中明细被外部修改。
        energyBreakdown = List.copyOf(energyBreakdown);
    }
}
