package com.ims.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.order.entity.OrderStatus;
import com.ims.order.entity.OutboxEvent;
import com.ims.order.entity.OutboxStatus;
import com.ims.order.repository.OutboxEventRepository;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the relay's contract with the outbox table. The repository is mocked on purpose and not just
 * for speed: its claim query is native Postgres ({@code FOR UPDATE SKIP LOCKED}) which H2 cannot
 * parse, so this is the only way to exercise the relay's logic in the normal test run.
 *
 * <p>The assertion that matters most is {@code sendsTheTypedEventObject...}: the row stores JSON, but
 * Kafka has to be handed the deserialized {@link OrderEvent}, because the producer's configured
 * TYPE_MAPPINGS is what turns that class into the agreed "order-event" type header. Hand it the
 * stored String instead and notification-service dead-letters every single event.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
        relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, objectMapper);
    }

    @Test
    void relayPendingEvents_marksTheRowSentAndStampsSentAtWhenKafkaAcknowledges() {
        OutboxEvent row = newRow(1L, "ORD-1");
        when(outboxEventRepository.lockNewBatch(anyInt())).thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(OrderEventPublisher.ORDER_EVENTS_TOPIC), eq("ORD-1"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relayPendingEvents();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getAttempts()).isZero();
        verify(outboxEventRepository).save(row);
    }

    @Test
    void relayPendingEvents_sendsTheTypedEventObjectKeyedByAggregateId() {
        OutboxEvent row = newRow(1L, "ORD-1");
        when(outboxEventRepository.lockNewBatch(anyInt())).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relayPendingEvents();

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("order-events"), eq("ORD-1"), value.capture());
        assertThat(value.getValue()).isInstanceOf(OrderEvent.class);
        assertThat(((OrderEvent) value.getValue()).eventId()).isEqualTo(row.getEventId());
    }

    @Test
    void relayPendingEvents_leavesTheRowNewCountsTheAttemptAndStopsTheBatchWhenTheSendFails() {
        OutboxEvent first = newRow(1L, "ORD-1");
        OutboxEvent second = newRow(2L, "ORD-2");
        when(outboxEventRepository.lockNewBatch(anyInt())).thenReturn(List.of(first, second));
        // The realistic broker-down shape: send() hands back a future that has already failed, so the
        // relay's five-second wait unwraps an ExecutionException immediately instead of burning five
        // real seconds on a future that never completes.
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker unreachable"));
        when(kafkaTemplate.send(anyString(), eq("ORD-1"), any())).thenReturn(failed);

        relay.relayPendingEvents();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getSentAt()).isNull();
        // The batch stopped: the queued second row was not even attempted, so a later order's event
        // cannot overtake an earlier one and a dead broker is not hit a hundred times per cycle.
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(second.getAttempts()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), eq("ORD-2"), any());
    }

    @Test
    void relayPendingEvents_skipsAnUnsendableRowWithoutBlockingTheRestOfTheBatch() {
        OutboxEvent unknown = newRow(1L, "ORD-1");
        unknown.setEventType("SomeEventFromTheFuture");
        OutboxEvent good = newRow(2L, "ORD-2");
        when(outboxEventRepository.lockNewBatch(anyInt())).thenReturn(List.of(unknown, good));
        when(kafkaTemplate.send(anyString(), eq("ORD-2"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relayPendingEvents();

        // A row we can never send is counted and stepped over. Stopping on it would wedge the relay
        // forever and starve every later order of its notification.
        assertThat(unknown.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(unknown.getAttempts()).isEqualTo(1);
        assertThat(good.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(kafkaTemplate, never()).send(anyString(), eq("ORD-1"), any());
    }

    @Test
    void relayPendingEvents_touchesNothingWhenTheOutboxIsEmpty() {
        when(outboxEventRepository.lockNewBatch(anyInt())).thenReturn(List.of());

        relay.relayPendingEvents();

        verifyNoInteractions(kafkaTemplate);
    }

    private OutboxEvent newRow(long id, String orderNumber) {
        String eventId = "evt-" + id;
        OrderEvent event = new OrderEvent(eventId, orderNumber, "buyer@example.com",
                OrderStatus.CONFIRMED, null, Instant.now());
        try {
            return OutboxEvent.builder()
                    .id(id)
                    .eventId(eventId)
                    .aggregateId(orderNumber)
                    .eventType(OrderEventPublisher.ORDER_EVENT_TYPE)
                    .topic(OrderEventPublisher.ORDER_EVENTS_TOPIC)
                    .payload(objectMapper.writeValueAsString(event))
                    .status(OutboxStatus.NEW)
                    .createdAt(Instant.now())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
