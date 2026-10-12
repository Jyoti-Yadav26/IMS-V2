package com.ims.order.event;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.order.entity.Order;
import com.ims.order.entity.OrderStatus;
import com.ims.order.entity.OutboxEvent;
import com.ims.order.entity.OutboxStatus;
import com.ims.order.exception.OutboxSerializationException;
import com.ims.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the dual-write fix at its source: placing an order must leave exactly one outbox row behind.
 * Before the fix this class sent straight to the broker from inside {@code createOrder}'s
 * transaction and only logged failures, so a confirmed order could exist with its event never
 * published and nothing but a log line to show for it.
 *
 * <p>"Nothing on the request thread talks to Kafka" is now structural rather than asserted - the
 * class no longer compiles against {@code KafkaTemplate} at all. {@code OutboxRelayTest} is where
 * the Kafka interaction is pinned.
 *
 * <p>The {@code ObjectMapper} is real, not a mock, and built the way Boot's autoconfiguration builds
 * the injected one: the payload's shape is part of the behaviour under test, so a stubbed serializer
 * would assert nothing. That is also why the publisher is constructed by hand instead of with
 * {@code @InjectMocks}.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
        publisher = new OrderEventPublisher(outboxEventRepository, objectMapper);
    }

    @Test
    void publish_writesExactlyOneNewOutboxRowForTheOrder() {
        publisher.publish(confirmedOrder());

        OutboxEvent row = capturedRow();
        assertThat(row.getTopic()).isEqualTo(OrderEventPublisher.ORDER_EVENTS_TOPIC);
        assertThat(row.getAggregateId()).isEqualTo("ORD-1");          // == the Kafka message key
        assertThat(row.getEventType()).isEqualTo(OrderEventPublisher.ORDER_EVENT_TYPE);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getEventId()).isNotBlank();
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    void publish_storesAPayloadThatDeserializesBackToTheEventWithTheRowsEventId() throws Exception {
        publisher.publish(confirmedOrder());

        OutboxEvent row = capturedRow();
        OrderEvent event = objectMapper.readValue(row.getPayload(), OrderEvent.class);
        // The column and the payload must carry the SAME id: that id is the consumer's
        // de-duplication key, and it is only trustworthy if the audit row agrees with the wire.
        assertThat(event.eventId()).isEqualTo(row.getEventId());
        assertThat(UUID.fromString(event.eventId())).isNotNull();
        assertThat(event.orderNumber()).isEqualTo("ORD-1");
        assertThat(event.customerEmail()).isEqualTo("buyer@example.com");
        assertThat(event.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(event.reason()).isNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void publish_generatesAFreshEventIdPerCall() {
        publisher.publish(confirmedOrder());
        publisher.publish(confirmedOrder());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(OutboxEvent::getEventId).doesNotHaveDuplicates();
    }

    /**
     * Not a nicety: committing the order while dropping its event is the exact bug the outbox
     * removes, so a serialization failure has to take the transaction down with it rather than be
     * logged and shrugged off.
     */
    @Test
    void publish_failsTheOrderWhenTheEventCannotBeSerialized() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(JsonMappingException.from((JsonGenerator) null, "boom"));
        OrderEventPublisher brokenPublisher = new OrderEventPublisher(outboxEventRepository, broken);

        assertThatThrownBy(() -> brokenPublisher.publish(confirmedOrder()))
                .isInstanceOf(OutboxSerializationException.class)
                .hasMessageContaining("ORD-1");
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    private OutboxEvent capturedRow() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Order confirmedOrder() {
        return Order.builder()
                .orderNumber("ORD-1")
                .customerEmail("buyer@example.com")
                .status(OrderStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(40))
                .build();
    }
}
