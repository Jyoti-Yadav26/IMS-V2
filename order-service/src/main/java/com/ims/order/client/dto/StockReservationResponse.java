package com.ims.order.client.dto;

import java.math.BigDecimal;

/**
 * Deliberate copy of inventory-service's response record (no shared library between services).
 *
 * <p>{@code unitPrice} is the trusted price: inventory owns it, so the order total is built from this
 * value and never from whatever the client put in the request.
 */
public record StockReservationResponse(boolean reserved, String sku, Integer remainingQuantity, String message,
                                       BigDecimal unitPrice) {
}
