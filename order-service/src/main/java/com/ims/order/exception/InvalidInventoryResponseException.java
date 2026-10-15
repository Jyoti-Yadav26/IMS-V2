package com.ims.order.exception;

/**
 * Raised when inventory-service answered promptly but the answer cannot be used - specifically when
 * it reports a line as reserved without telling us the {@code unitPrice}.
 *
 * <p>Deliberately NOT an {@link InventoryServiceUnavailableException}: nothing was unavailable, the
 * contract was broken (most likely a version skew, a new order-service against an older
 * inventory-service). It is still treated as a FAILED order rather than a REJECTED one, because it is
 * not a business decision about stock - we simply cannot price the order, and the one thing we must
 * never do is fall back to the price the client sent.
 *
 * <p>Caught inside {@code OrderServiceImpl.createOrder} and recorded as the order's failure reason, so
 * like {@link InsufficientStockException} it never reaches the HTTP layer and needs no handler.
 */
public class InvalidInventoryResponseException extends RuntimeException {
    public InvalidInventoryResponseException(String message) {
        super(message);
    }
}
