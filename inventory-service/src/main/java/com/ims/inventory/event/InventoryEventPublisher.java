package com.ims.inventory.event;

import com.ims.inventory.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventPublisher {

    public static final String LOW_STOCK_TOPIC = "low-stock-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Stamps each event with an id so notification-service can de-duplicate on it.
     *
     * <p>Worth being precise about how much weaker this is than order-service's equivalent. There the
     * id is written to the outbox inside the order's transaction and re-used on every resend, so it is
     * durable and a redelivery is provably the same event. Here there is no outbox: the id is minted at
     * send time, which de-duplicates a broker-level redelivery of this record but nothing above it. If
     * {@code StockReservationExecutor.attemptReservation} is retried by {@code ProductServiceImpl}'s
     * optimistic-lock loop after this line ran, the republished event gets a fresh id and the admin is
     * alerted twice. And since this call sits inside that {@code @Transactional} method, the send and
     * the stock decrement are still two independent commits - the same dual write the outbox removed
     * on the order side (see {@code OutboxEvent}'s javadoc). Both are deliberate: a duplicate low-stock
     * alert to an internal mailbox does not justify a second outbox.
     */
    public void publishLowStock(Product product) {
        String eventId = UUID.randomUUID().toString();
        LowStockEvent event = new LowStockEvent(
                eventId,
                product.getSku(),
                product.getName(),
                product.getQuantity(),
                product.getReorderThreshold(),
                Instant.now()
        );
        // Key by SKU so all events for the same product land on the same partition and
        // are processed in order by a single consumer instance.
        kafkaTemplate.send(LOW_STOCK_TOPIC, product.getSku(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish LowStockEvent for sku={} eventId={}", product.getSku(), eventId, ex);
                    } else {
                        log.info("Published LowStockEvent for sku={} remainingQuantity={} eventId={}",
                                product.getSku(), product.getQuantity(), eventId);
                    }
                });
    }
}
