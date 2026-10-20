package com.voltbridge.infra.messaging.kafka;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.device.event.DeviceEvent;
import com.voltbridge.device.port.DeviceEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Gateway 的 Kafka 出站适配器。
 *
 * <p>调用方只发布规范事件；发送操作交给 Kafka 客户端异步执行，Netty I/O 线程不会等待 broker 确认。</p>
 */
@Component
@ConditionalOnProperty(name = "voltbridge.messaging.kafka.publisher-enabled", havingValue = "true")
public class KafkaDeviceEventPublisher implements DeviceEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaDeviceEventPublisher.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String topic;

    public KafkaDeviceEventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate,
                                     @org.springframework.beans.factory.annotation.Value("${voltbridge.messaging.kafka.device-events-topic:voltbridge.device.events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(DeviceEvent event) {
        byte[] payload = DeviceProtoMapper.toProto(event).toByteArray();
        kafkaTemplate.send(topic, event.deviceId().value(), payload).whenComplete((result, error) -> {
            if (error == null) {
                LOGGER.debug("Device event published: eventId={}, deviceCode={}, topic={}, partition={}",
                        event.eventId(), event.deviceId().value(), topic, result.getRecordMetadata().partition());
            } else {
                LOGGER.error("Device event publish failed: eventId={}, deviceCode={}, topic={}",
                        event.eventId(), event.deviceId().value(), topic, error);
            }
        });
    }
}
