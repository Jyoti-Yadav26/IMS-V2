package com.ims.notification.event;

import java.time.Instant;

/**
 * Mirrors inventory-service's published contract for the "low-stock-events" topic.
 *
 * <p>{@code eventId} is the de-duplication key, same as on {@link OrderEvent}, so both topics go
 * through one idempotent path in {@code NotificationService}. Unlike the order side it is generated at
 * send time rather than stored - inventory-service has no outbox - so see
 * {@code InventoryEventPublisher} for what that id does and does not protect against.
 */
public record LowStockEvent(
        String eventId,
        String sku,
        String productName,
        Integer remainingQuantity,
        Integer reorderThreshold,
        Instant occurredAt
) {
}
