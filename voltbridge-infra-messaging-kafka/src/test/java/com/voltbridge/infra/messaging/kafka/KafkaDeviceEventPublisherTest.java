package com.voltbridge.infra.messaging.kafka;

import com.voltbridge.contract.mapper.DeviceProtoMapper;
import com.voltbridge.contract.v1.DeviceEventEnvelope;
import com.voltbridge.device.event.HeartbeatEvent;
import com.voltbridge.device.model.DeviceId;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用 Embedded Kafka 验证 Gateway 事件发布的实际 broker 边界及设备分区键。 */
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, topics = KafkaDeviceEventPublisherTest.TOPIC)
class KafkaDeviceEventPublisherTest {

    static final String TOPIC = "voltbridge.device.events";

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void publishesCanonicalEventWithDeviceCodeKey() {
        Map<String, Object> producerProperties = KafkaTestUtils.producerProps(broker);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        DefaultKafkaProducerFactory<String, byte[]> factory = new DefaultKafkaProducerFactory<>(producerProperties);
        try (KafkaConsumer<String, byte[]> consumer = consumer(broker)) {
            KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(factory);
            consumer.subscribe(java.util.List.of(TOPIC));
            Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
            HeartbeatEvent event = new HeartbeatEvent(UUID.randomUUID(), new DeviceId("OCPP-TEST-01"), "session-1", now, now);

            new KafkaDeviceEventPublisher(template, TOPIC).publish(event);
            var records = consumer.poll(Duration.ofSeconds(10));

            assertThat(records).hasSize(1);
            var record = records.iterator().next();
            assertThat(record.key()).isEqualTo("OCPP-TEST-01");
            assertThat(DeviceProtoMapper.fromProto(DeviceEventEnvelope.parseFrom(record.value()))).isEqualTo(event);
        } catch (Exception exception) {
            throw new AssertionError("Kafka canonical event round trip failed", exception);
        } finally {
            factory.destroy();
        }
    }

    private KafkaConsumer<String, byte[]> consumer(EmbeddedKafkaBroker broker) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps("publisher-test", "false", broker);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
    }
}
