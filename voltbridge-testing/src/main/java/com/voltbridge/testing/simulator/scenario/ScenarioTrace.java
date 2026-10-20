package com.voltbridge.testing.simulator.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 人类可读的外部设备事件轨迹，同时供测试失败时附带诊断信息。 */
public final class ScenarioTrace {

    private final Instant startedAt = Instant.now();
    private final List<String> entries = new CopyOnWriteArrayList<>();

    public void add(String message) {
        Duration elapsed = Duration.between(startedAt, Instant.now());
        entries.add("[%02d:%02d] %s".formatted(elapsed.toMinutesPart(), elapsed.toSecondsPart(), message));
    }

    public List<String> entries() { return List.copyOf(entries); }

    public String render() { return String.join(System.lineSeparator(), entries); }
}
