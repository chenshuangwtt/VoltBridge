package com.voltbridge.device.event;

import java.math.BigDecimal;

/**
 * 可扩展的分段计量明细，用于承载尖、峰、平、谷等协议费率区间。
 */
public record EnergyBreakdownItem(String category, BigDecimal energyKwh,
                                  BigDecimal amount, BigDecimal unitPrice) {
}
