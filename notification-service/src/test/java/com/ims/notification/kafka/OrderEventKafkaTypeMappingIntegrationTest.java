package com.ims.notification.kafka;

import com.ims.notification.entity.Notification;
import com.ims.notification.entity.NotificationType;
import com.ims.notification.repository.NotificationRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the real serialization boundary between order-service and notification-service.
 *
 * <p>OrderEventListenerTest calls the listener method directly with an already-built event, so
 * it cannot see deserialization problems. This test publishes to an embedded broker through a
 * producer configured exactly like order-service's KafkaProducerConfig, so the "__TypeId__"
 * header - and therefore the consumer's type resolution - is the real thing.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
                OrderEventKafkaTypeMappingIntegrationTest.ORDER_EVENTS,
                OrderEventKafkaTypeMappingIntegrationTest.ORDER_EVENTS_DLT,
                "low-stock-events"
        })
@DirtiesContext
class OrderEventKafkaTypeMappingIntegrationTest {

    static final String ORDER_EVENTS = "order-events";
    static final String ORDER_EVENTS_DLT = "order-events.DLT";

    /** The logical type token both sides agree on - see KafkaProducerConfig/KafkaConsumerConfig. */
    private static final String TYPE_MAPPING = "order-event:com.ims.order.event.OrderEvent";

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private final List<DefaultKafkaProducerFactory<String, Object>> producerFactories = new ArrayList<>();

    @BeforeEach
    void waitForListenersToBeAssigned() {
        registry.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    }

    @AfterEach
    void closeProducers() {
        producerFactories.forEach(DefaultKafkaProducerFactory::destroy);
        producerFactories.clear();
    }

    @Test
    void orderEventWithMappedTypeToken_isPersistedAsNotification() {
        String orderNumber = "ORD-MAPPED-1";

        publish(orderServiceProducer(true), orderNumber, "CONFIRMED", null);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(notificationsFor(orderNumber)).hasSize(1));

        Notification saved = notificationsFor(orderNumber).get(0);
        assertThat(saved.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(saved.getRecipient()).isEqualTo("buyer@example.com");
        assertThat(saved.getSubject()).contains(orderNumber);
    }

    @Test
    void orderEventWithRawClassNameHeader_isRoutedToDeadLetterTopic() {
        String legacyOrderNumber = "ORD-LEGACY-1";
        String sentinelOrderNumber = "ORD-SENTINEL-1";

        try (Consumer<String, String> dltConsumer = dltConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dltConsumer, ORDER_EVENTS_DLT);

            // A producer with no type mapping stamps its own FQCN into "__TypeId__", which is not
            // in notification-service's trusted packages, so this record can never be read.
            publish(orderServiceProducer(false), legacyOrderNumber, "CONFIRMED", null);

            // Single partition means strict ordering, and DeadLetterPublishingRecoverer waits for
            // its send result. So once the sentinel lands, the legacy record has already been
            // dead-lettered - no sleeping, and the negative assertion below cannot pass vacuously.
            publish(orderServiceProducer(true), sentinelOrderNumber, "REJECTED", "out of stock");
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(notificationsFor(sentinelOrderNumber)).hasSize(1));

            assertThat(notificationsFor(legacyOrderNumber)).isEmpty();

            ConsumerRecords<String, String> records =
                    KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(10), 1);
            List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();
            records.records(ORDER_EVENTS_DLT).forEach(dltRecords::add);
            assertThat(dltRecords).hasSize(1);
            ConsumerRecord<String, String> dead = dltRecords.get(0);
            assertThat(dead.key()).isEqualTo(legacyOrderNumber);
            assertThat(stacktraceHeaderOf(dead))
                    .contains("The class 'com.ims.order.event.OrderEvent' is not in the trusted packages");
        }
    }

    private void publish(KafkaTemplate<String, Object> template, String orderNumber,
                         String status, String reason) {
        var event = new com.ims.order.event.OrderEvent(
                orderNumber, "buyer@example.com", status, reason, Instant.now());
        template.send(ORDER_EVENTS, orderNumber, event);
        template.flush();
    }

    /**
     * Mirrors order-service's KafkaProducerConfig field for field. withTypeMapping=false
     * reproduces how order-service published before the type mapping was added.
     */
    private KafkaTemplate<String, Object> orderServiceProducer(boolean withTypeMapping) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        if (withTypeMapping) {
            props.put(JsonSerializer.TYPE_MAPPINGS, TYPE_MAPPING);
        }
        var factory = new DefaultKafkaProducerFactory<String, Object>(props);
        producerFactories.add(factory);
        return new KafkaTemplate<>(factory);
    }

    private Consumer<String, String> dltConsumer() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "dlt-inspector", "true");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }

    /** DeadLetterPublishingRecoverer records why the record could not be read. */
    private String stacktraceHeaderOf(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        assertThat(header).as("DLT exception stacktrace header").isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private List<Notification> notificationsFor(String orderNumber) {
        return notificationRepository.findAll().stream()
                .filter(n -> orderNumber.equals(n.getReferenceId()))
                .toList();
    }
}
