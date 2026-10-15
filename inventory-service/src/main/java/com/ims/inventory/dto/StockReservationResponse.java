package com.ims.inventory.dto;

import java.math.BigDecimal;

/**
 * Answer to {@code POST /api/inventory/reserve}.
 *
 * <p>{@code unitPrice} is this service's {@code Product.price} - inventory owns the price, so the
 * caller never has to be trusted with it. On an idempotent replay it is the price stored on the
 * reservation ledger row, i.e. the price as of the original reservation, not the current one.
 */
public record StockReservationResponse(
        boolean reserved,
        String sku,
        Integer remainingQuantity,
        String message,
        BigDecimal unitPrice
) {
}
