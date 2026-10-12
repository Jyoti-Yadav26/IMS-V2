package com.ims.order.event;

import java.time.Instant;

/**
 * Test-only stand-in for order-service's published OrderEvent, used by
 * {@code OrderEventKafkaTypeMappingIntegrationTest}. Deliberately declared in order-service's
 * package so that a JsonSerializer configured exactly like order-service's produces a
 * byte-identical record - including the "__TypeId__" header - without notification-service
 * taking a module dependency on order-service.
 *
 * <p>{@code status} is a String here where order-service uses the OrderStatus enum; Jackson
 * writes both as the same JSON string, so the payload is unchanged.
 *
 * <p>{@code eventId} mirrors the field order-service added when its publish became an outbox write:
 * it is the stable, per-event id the consumer will de-duplicate on. Keep the component order
 * identical to the producer's record - this copy exists precisely to catch a drift between them.
 */
public record OrderEvent(
        String eventId,
        String orderNumber,
        String customerEmail,
        String status,
        String reason,
        Instant occurredAt
) {
}
