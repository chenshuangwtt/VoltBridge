package com.voltbridge.infra.messaging.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.core.KafkaTemplate;

/** Kafka 消费失败策略：三次指数退避后写入同集群的 <code>.DLT</code> 主题。 */
@Configuration
@ConditionalOnProperty(name = "voltbridge.messaging.kafka.consumer-enabled", havingValue = "true")
public class KafkaConsumerRetryConfiguration {

    @Bean
    public DefaultErrorHandler kafkaDeviceEventErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0D);
        backOff.setMaxInterval(4_000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
