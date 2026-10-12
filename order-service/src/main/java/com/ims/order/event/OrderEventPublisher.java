package com.ims.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.order.entity.Order;
import com.ims.order.entity.OutboxEvent;
import com.ims.order.entity.OutboxStatus;
import com.ims.order.exception.OutboxSerializationException;
import com.ims.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the order event instead of publishing it.
 *
 * <p>This class used to call {@code kafkaTemplate.send(...)} from inside {@code createOrder}'s
 * transaction, fire-and-forget, logging failures. That is a dual write: the order row and the Kafka
 * record were two independent commits, so a broker outage produced a confirmed order whose customer
 * was never told, and a rollback after a successful send produced a notification for an order that
 * does not exist. It now writes one {@link OutboxEvent} row through the same {@code EntityManager},
 * and therefore the same transaction, as the order - either both facts are durable or neither is.
 * {@code OrderServiceImpl} is untouched: it still calls {@code publish(saved)} on one line.
 *
 * <p>There is deliberately no {@code KafkaTemplate} here any more. Nothing on the request thread may
 * touch the broker; {@link OutboxRelay} owns that, after the commit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    public static final String ORDER_EVENTS_TOPIC = "order-events";

    /**
     * Logical name of the contract stored on the outbox row, which {@link OutboxRelay} maps back to
     * {@link OrderEvent} so it can hand Kafka a typed object. Kept separate from the
     * {@code JsonSerializer.TYPE_MAPPINGS} token ("order-event"): that one is the wire contract with
     * notification-service, this one is private bookkeeping, and conflating them would mean a change
     * to either had to be a change to both.
     */
    public static final String ORDER_EVENT_TYPE = "OrderEvent";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publish(Order order) {
        String eventId = UUID.randomUUID().toString();
        OrderEvent event = new OrderEvent(
                eventId,
                order.getOrderNumber(),
                order.getCustomerEmail(),
                order.getStatus(),
                order.getFailureReason(),
                Instant.now()
        );
        OutboxEvent row = OutboxEvent.builder()
                .eventId(eventId)
                .aggregateId(order.getOrderNumber())
                .eventType(ORDER_EVENT_TYPE)
                .topic(ORDER_EVENTS_TOPIC)
                .payload(serialize(event))
                .status(OutboxStatus.NEW)
                .build();
        outboxEventRepository.save(row);
        log.info("Queued OrderEvent order={} status={} eventId={}",
                order.getOrderNumber(), order.getStatus(), eventId);
    }

    /**
     * The same {@code eventId} goes into the column and into the payload, so the consumer's
     * de-duplication key and our own audit row can never disagree.
     */
    private String serialize(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new OutboxSerializationException(
                    "Could not serialize OrderEvent for order '" + event.orderNumber() + "'", ex);
        }
    }
}
