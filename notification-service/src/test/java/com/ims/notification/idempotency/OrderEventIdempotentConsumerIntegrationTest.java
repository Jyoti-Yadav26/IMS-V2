package com.ims.notification.idempotency;

import com.ims.notification.entity.Notification;
import com.ims.notification.entity.NotificationType;
import com.ims.notification.repository.NotificationRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Proves the consumer is idempotent: order-service's outbox relay is at-least-once, so the same
 * OrderEvent - same stored eventId - can arrive twice, and that must not email the customer twice.
 *
 * <p>Two separate records carrying one eventId is not a test contrivance, it is the actual failure
 * mode. A healthy consumer group delivers each record once; a redelivery needs a crash before the
 * offset commit, or a rebalance. What really happens is that {@code OutboxRelay} sends a row, crashes
 * before marking it SENT, and sends the same stored event again as a new record. That is what this
 * test publishes.
 *
 * <p>Scope, stated honestly: this exercises the {@code existsByEventId} pre-check, not the
 * unique-constraint catch. The duplicate is consumed after the first record's transaction has
 * committed, so the pre-check always wins and the constraint is never tripped - the catch is only
 * reachable when two consumers race, which is covered by {@code NotificationServiceTest}. Forcing
 * that race on an embedded broker would mean stubbing the repository inside a shared context, which
 * would prove less than the unit test already does.
 *
 * <p>Separate from {@code OrderEventKafkaTypeMappingIntegrationTest}, whose scope is the
 * serialization boundary, but it reuses that class's producer helper so the records on the wire -
 * including the type-id header - are the ones order-service would really write.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
                OrderEventIdempotentConsumerIntegrationTest.ORDER_EVENTS,
                OrderEventIdempotentConsumerIntegrationTest.ORDER_EVENTS_DLT,
                "low-stock-events"
        })
@DirtiesContext
class OrderEventIdempotentConsumerIntegrationTest {

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
    void setUp() {
        // The test H2 database outlives a context restart within the JVM, so start from a known state
        // rather than relying on class ordering.
        notificationRepository.deleteAll();
        registry.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
    }

    @AfterEach
    void closeProducers() {
        producerFactories.forEach(DefaultKafkaProducerFactory::destroy);
        producerFactories.clear();
    }

    @Test
    void theSameOrderEventDeliveredTwice_createsExactlyOneNotification() {
        String orderNumber = "ORD-DUP-1";
        String eventId = UUID.randomUUID().toString();
        String sentinelOrderNumber = "ORD-DUP-SENTINEL";

        publish(eventId, orderNumber, "CONFIRMED", null);
        publish(eventId, orderNumber, "CONFIRMED", null);

        // The sentinel is what stops the assertion below passing vacuously. One partition means every
        // record lands on partition 0 regardless of key, and one partition means one assigned consumer
        // even once a later step raises the container concurrency - so the records are consumed in
        // publish order. Once the sentinel has a notification, both duplicates are already done with.
        publish(UUID.randomUUID().toString(), sentinelOrderNumber, "REJECTED", "out of stock");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(notificationsFor(sentinelOrderNumber)).hasSize(1));

        List<Notification> notifications = notificationsFor(orderNumber);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getEventId()).isEqualTo(eventId);
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
    }

    @Test
    void twoDistinctOrderEventsForTheSameOrder_bothNotify() {
        // The partner to the test above: de-duplicating on referenceId would make that one pass too,
        // and would silence the follow-up event for an order that changed state.
        String orderNumber = "ORD-DISTINCT-1";
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        publish(first, orderNumber, "FAILED", "inventory-service down");
        publish(second, orderNumber, "CONFIRMED", null);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(notificationsFor(orderNumber)).hasSize(2));
        assertThat(notificationsFor(orderNumber))
                .extracting(Notification::getEventId)
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void theEventIdColumnIsUniqueButNullable() {
        // The pre-check above is only safe because of this constraint, and it is also what produces
        // the exception shape NotificationServiceTest fabricates. Asserted through the repository
        // because the consumer path never reaches it sequentially.
        notificationRepository.saveAndFlush(notificationWithEventId("evt-dup"));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(notificationWithEventId("evt-dup")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Notifications with no event id are unconstrained: NULLs count as distinct, which is what
        // lets the column be added to an existing table and lets a missing id fail open.
        notificationRepository.saveAndFlush(notificationWithEventId(null));
        notificationRepository.saveAndFlush(notificationWithEventId(null));
        assertThat(notificationRepository.count()).isEqualTo(3);
    }

    private Notification notificationWithEventId(String eventId) {
        return Notification.builder()
                .type(NotificationType.ORDER_CONFIRMED)
                .recipient("buyer@example.com")
                .subject("Your order ORD-CONSTRAINT is CONFIRMED")
                .message("Good news! Your order has been confirmed.")
                .referenceId("ORD-CONSTRAINT")
                .eventId(eventId)
                .build();
    }

    private void publish(String eventId, String orderNumber, String status, String reason) {
        var event = new com.ims.order.event.OrderEvent(
                eventId, orderNumber, "buyer@example.com", status, reason, Instant.now());
        KafkaTemplate<String, Object> template = orderServiceProducer();
        template.send(ORDER_EVENTS, orderNumber, event);
        template.flush();
    }

    /** Mirrors order-service's KafkaProducerConfig field for field, type mapping included. */
    private KafkaTemplate<String, Object> orderServiceProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(JsonSerializer.TYPE_MAPPINGS, TYPE_MAPPING);
        var factory = new DefaultKafkaProducerFactory<String, Object>(props);
        producerFactories.add(factory);
        return new KafkaTemplate<>(factory);
    }

    private List<Notification> notificationsFor(String orderNumber) {
        return notificationRepository.findAll().stream()
                .filter(n -> orderNumber.equals(n.getReferenceId()))
                .toList();
    }
}
