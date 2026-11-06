package com.voltbridge.business.domain.billing;

import com.voltbridge.common.exception.VoltBridgeException;

import java.math.BigDecimal;
import java.util.Objects;

/** 资费方案中的一个连续日内时段，边界以本地自然日分钟数表示。 */
public record TariffPeriod(int sequenceNo, TariffPeriodType periodType, int startMinute, int endMinute,
                           BigDecimal energyPricePerKwh, BigDecimal serviceFeePerKwh) {
    public TariffPeriod {
        if (sequenceNo < 1) {
            throw new VoltBridgeException("TARIFF_PERIOD_INVALID", "Tariff period sequence must start at 1");
        }
        periodType = Objects.requireNonNull(periodType, "periodType");
        if (startMinute < 0 || startMinute >= 1440 || endMinute <= 0 || endMinute > 1440
                || startMinute >= endMinute) {
            throw new VoltBridgeException("TARIFF_PERIOD_INVALID", "Tariff period bounds must satisfy 0 <= start < end <= 1440");
        }
        if (energyPricePerKwh == null || energyPricePerKwh.signum() < 0
                || serviceFeePerKwh == null || serviceFeePerKwh.signum() < 0) {
            throw new VoltBridgeException("TARIFF_PERIOD_PRICE_INVALID", "Tariff period prices must be non-negative");
        }
        energyPricePerKwh = BillingDecimalPolicy.unitPrice(energyPricePerKwh, "energyPricePerKwh");
        serviceFeePerKwh = BillingDecimalPolicy.unitPrice(serviceFeePerKwh, "serviceFeePerKwh");
    }

    public String startTimeLabel() {
        return formatMinute(startMinute);
    }

    public String endTimeLabel() {
        return formatMinute(endMinute);
    }

    private static String formatMinute(int minute) {
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }
}
