package com.ims.notification.event;

import java.time.Instant;

/**
 * Mirrors order-service's published contract for the "order-events" topic.
 *
 * <p>{@code eventId} is generated once by order-service when the event is written to its outbox, so
 * it is stable across redeliveries - the relay can legitimately send the same event twice, because it
 * may crash after Kafka acknowledged but before the outbox row is marked SENT. This field is the hook
 * the listener will de-duplicate on (CLAUDE.md issue 13); the listener is deliberately NOT changed
 * here, so for now duplicates still produce duplicate notifications.
 */
public record OrderEvent(
        String eventId,
        String orderNumber,
        String customerEmail,
        String status,       // String here, enum on the producer side
        String reason,
        Instant occurredAt
) {
}
