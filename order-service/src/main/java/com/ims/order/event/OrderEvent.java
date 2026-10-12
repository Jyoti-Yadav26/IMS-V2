package com.ims.order.event;

import com.ims.order.entity.OrderStatus;

import java.time.Instant;

/**
 * Published once an order reaches a terminal state (CONFIRMED / REJECTED / FAILED).
 * notification-service consumes this asynchronously to email the customer - order-service
 * doesn't wait on, or even know about, that side effect, so a slow/down notification
 * pipeline never blocks or fails order placement.
 *
 * <p>{@code eventId} is generated once, when the event is written to the outbox, and never again.
 * The relay may legitimately redeliver the same event - it can crash after Kafka acknowledged the
 * send but before the outbox row is marked SENT - so this is the key the consumer de-duplicates on.
 * A value regenerated per send would make every redelivery look like a brand new event and defeat
 * the point entirely.
 */
public record OrderEvent(
        String eventId,
        String orderNumber,
        String customerEmail,
        OrderStatus status,
        String reason,
        Instant occurredAt
) {
}
