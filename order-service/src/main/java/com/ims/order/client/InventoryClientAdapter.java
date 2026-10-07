package com.ims.order.client;

import com.ims.order.client.dto.StockReservationRequest;
import com.ims.order.client.dto.StockReservationResponse;
import com.ims.order.exception.InsufficientStockException;
import com.ims.order.exception.InventoryServiceUnavailableException;
import com.ims.order.exception.ProductNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resilience boundary between order-service and inventory-service. Both annotations are
 * applied to the same "inventoryService" instance (configured in application.yml), and the
 * aspect order there is overridden so that the circuit breaker wraps the retry:
 * <ul>
 *   <li>{@code @Retry} (inner) retries transient failures a bounded number of times with
 *       backoff (e.g. a single dropped packet or GC pause on the other side).</li>
 *   <li>{@code @CircuitBreaker} (outer) sees one outcome per logical call - the result after
 *       retries are exhausted - and tracks the failure rate across those. Once it trips,
 *       calls fail fast into the fallback for a cool-down window without entering the retry
 *       layer at all, instead of piling up load on a struggling downstream.</li>
 * </ul>
 * Business exceptions (insufficient stock / unknown SKU) are configured as
 * "ignoreExceptions" for both, so they are never retried or counted as circuit-breaker
 * failures - but note that ignoring them does not stop the fallback from being invoked, which
 * is why the fallback rethrows them unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryClientAdapter {

    private static final String RESILIENCE_INSTANCE = "inventoryService";

    private final InventoryClient inventoryClient;

    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "reserveStockFallback")
    @Retry(name = RESILIENCE_INSTANCE)
    public StockReservationResponse reserveStock(StockReservationRequest request) {
        return inventoryClient.reserveStock(request);
    }

    // No @Retry: restock is the compensating half of the saga and is not idempotent, so a
    // retry after a lost response would release the same stock twice.
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "restockFallback")
    public void restock(String sku, int quantity) {
        inventoryClient.restock(sku, quantity);
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private StockReservationResponse reserveStockFallback(StockReservationRequest request, Throwable t) {
        // Resilience4j invokes the fallback for every exception the call throws, including the
        // ones listed under ignore-exceptions (those only stay out of the breaker's statistics).
        // So business rejections have to be rethrown as-is here, or a REJECTED order would be
        // misreported as a FAILED one.
        if (t instanceof InsufficientStockException || t instanceof ProductNotFoundException) {
            throw (RuntimeException) t;
        }
        log.error("inventory-service unavailable while reserving sku={}: {}", request.sku(), t.toString());
        throw new InventoryServiceUnavailableException("inventory-service is currently unavailable", t);
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private void restockFallback(String sku, int quantity, Throwable t) {
        // Compensation is best-effort: if inventory-service is down, the compensating
        // restock will simply fail too. We log loudly so an operator/reconciliation job
        // can fix the drift instead of silently losing stock.
        log.error("COMPENSATION FAILED: could not restock sku={} quantity={} after order failure: {}",
                sku, quantity, t.toString());
    }
}
