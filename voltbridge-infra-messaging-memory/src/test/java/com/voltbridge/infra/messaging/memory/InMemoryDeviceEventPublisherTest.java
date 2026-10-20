package com.voltbridge.infra.messaging.memory;

import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.DeviceId;
import com.voltbridge.device.port.DeviceEventHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDeviceEventPublisherTest {

    @Test
    void dispatchesOnlyToMatchingTypedHandlersAndCatchAllSubscribers() {
        AtomicInteger typedCount = new AtomicInteger();
        AtomicInteger catchAllCount = new AtomicInteger();
        DeviceEventHandler<HeartbeatEvent> heartbeatHandler = new DeviceEventHandler<>() {
            @Override
            public Class<HeartbeatEvent> eventType() {
                return HeartbeatEvent.class;
            }

            @Override
            public void handle(HeartbeatEvent event) {
                typedCount.incrementAndGet();
            }
        };
        DeviceEventHandler<DeviceEvent> catchAll = event -> catchAllCount.incrementAndGet();
        InMemoryDeviceEventPublisher publisher = new InMemoryDeviceEventPublisher(
                List.of(heartbeatHandler, catchAll));

        publisher.publish(new HeartbeatEvent(UUID.randomUUID(), new DeviceId("20231212000010"),
                "session-1", null, Instant.now()));

        assertThat(typedCount).hasValue(1);
        assertThat(catchAllCount).hasValue(1);
    }
}
