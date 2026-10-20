package com.voltbridge.common.time;

import java.time.Instant;

@FunctionalInterface
public interface TimeProvider {

    Instant now();

    static TimeProvider system() {
        return Instant::now;
    }
}
