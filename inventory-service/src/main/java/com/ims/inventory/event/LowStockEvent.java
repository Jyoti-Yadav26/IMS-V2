package com.ims.inventory.event;

import java.time.Instant;

/**
 * Published to Kafka whenever a reservation brings a product's quantity at or below
 * its reorder threshold. This is fire-and-forget / eventually-consistent by design:
 * inventory-service doesn't need (or want) to know who is listening. notification-service
 * happens to consume it today to alert an admin, but any number of future consumers
 * (e.g. an auto-reordering service) could subscribe without inventory-service changing.
 *
 * <p>{@code eventId} identifies this event so consumers can de-duplicate on it, mirroring
 * {@code OrderEvent}. Jackson matches by name, so adding it in first position changes nothing on the
 * wire: a payload published before this field existed simply deserializes it as null, and a consumer
 * that does not know the field ignores it. See {@link InventoryEventPublisher} for the one caveat -
 * without an outbox, this id is generated at send time rather than stored.
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
