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
 */
public record OrderEvent(
        String orderNumber,
        String customerEmail,
        String status,
        String reason,
        Instant occurredAt
) {
}
