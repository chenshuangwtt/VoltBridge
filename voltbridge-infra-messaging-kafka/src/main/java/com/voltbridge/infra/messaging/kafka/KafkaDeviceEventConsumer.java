package com.voltbridge.infra.messaging.kafka;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.DeviceEventEnvelope;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.port.DeviceEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Business Service 的 Kafka 入站适配器。
 *
 * <p>解码后的事件仍交给已有的 {@link DeviceEventPublisher} 类型分发器，确保单体和分布式复用相同业务处理器。</p>
 */
@Component
@ConditionalOnProperty(name = "voltbridge.messaging.kafka.consumer-enabled", havingValue = "true")
public class KafkaDeviceEventConsumer {

    private final DeviceEventPublisher applicationEventDispatcher;

    public KafkaDeviceEventConsumer(DeviceEventPublisher applicationEventDispatcher) {
        this.applicationEventDispatcher = applicationEventDispatcher;
    }

    @KafkaListener(topics = "${voltbridge.messaging.kafka.device-events-topic:voltbridge.device.events}",
            groupId = "${voltbridge.messaging.kafka.consumer-group:voltbridge-business}")
    public void consume(byte[] payload) throws Exception {
        DeviceEvent event = DeviceProtoMapper.fromProto(DeviceEventEnvelope.parseFrom(payload));
        applicationEventDispatcher.publish(event);
    }
}
