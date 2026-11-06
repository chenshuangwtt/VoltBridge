package com.voltbridge.business.domain.billing;

import com.voltbridge.common.exception.VoltBridgeException;

import java.time.Instant;
import java.util.Objects;

/** 资费方案的左闭右开有效区间；null 终点代表正无穷。 */
public record TariffEffectiveRange(Instant from, Instant to) {
    public TariffEffectiveRange {
        Objects.requireNonNull(from, "from");
        if (to != null && !to.isAfter(from)) {
            throw new VoltBridgeException("TARIFF_EFFECTIVE_RANGE_INVALID", "effectiveTo must be after effectiveFrom");
        }
    }

    public boolean overlaps(TariffEffectiveRange other) {
        Objects.requireNonNull(other, "other");
        return (to == null || other.from.isBefore(to))
                && (other.to == null || from.isBefore(other.to));
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(from) && (to == null || instant.isBefore(to));
    }

    public String toIntervalString() {
        return "[" + from + "," + (to == null ? "+∞" : to) + ")";
    }
}
