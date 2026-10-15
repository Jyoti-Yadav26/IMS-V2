package com.ims.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * @param unitPrice accepted but <strong>ignored</strong>. The price of a line item comes from
 *                  inventory-service's reservation response, never from the caller - a client that
 *                  could name its own price could buy anything for a penny. The field is kept, and
 *                  deliberately left unvalidated and optional, only so that clients written against
 *                  the older contract keep working; nothing ever reads it.
 */
public record OrderItemRequest(
        @NotBlank(message = "sku is required") String sku,
        @NotNull @Min(value = 1, message = "quantity must be >= 1") Integer quantity,
        BigDecimal unitPrice
) {
}
