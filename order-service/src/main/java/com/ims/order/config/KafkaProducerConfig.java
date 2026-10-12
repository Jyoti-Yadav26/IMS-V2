package com.ims.order.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // OutboxRelay is now the only producer, and it waits 5s on each send. The default
        // max.block.ms of 60s is spent *inside* send() waiting for cluster metadata, before any
        // future exists, so with the broker down that 5s timeout would never get a say and every
        // relay cycle would stall a full minute. 5s keeps the relay responsive and makes the
        // broker-unreachable path testable in bounded time.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        // Stamp a stable logical type token into the "__TypeId__" header instead of this
        // service's own class name, so consumers map the token to their own copy of the
        // contract and aren't coupled to order-service's package layout.
        props.put(JsonSerializer.TYPE_MAPPINGS, "order-event:com.ims.order.event.OrderEvent");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
