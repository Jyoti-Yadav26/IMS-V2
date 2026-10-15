package com.ims.inventory.service.impl;

import com.ims.inventory.dto.StockReservationRequest;
import com.ims.inventory.dto.StockReservationResponse;
import com.ims.inventory.entity.Product;
import com.ims.inventory.entity.ReservationStatus;
import com.ims.inventory.entity.StockReservation;
import com.ims.inventory.event.InventoryEventPublisher;
import com.ims.inventory.exception.InsufficientStockException;
import com.ims.inventory.exception.ProductNotFoundException;
import com.ims.inventory.repository.ProductRepository;
import com.ims.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Split out from {@link ProductServiceImpl} so that {@code @Transactional} is applied
 * through a real Spring proxy: calling an {@code @Transactional} method on {@code this}
 * from within the same class bypasses the proxy and silently runs without a transaction,
 * which would defeat the optimistic-locking retry loop in the caller.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class StockReservationExecutor {

    private final ProductRepository productRepository;
    private final StockReservationRepository reservationRepository;
    private final InventoryEventPublisher eventPublisher;

    @Transactional
    public StockReservationResponse attemptReservation(StockReservationRequest request) {
        Product product = productRepository.findBySku(request.sku())
                .orElseThrow(() -> new ProductNotFoundException("Product with sku '" + request.sku() + "' not found"));

        if (product.getQuantity() < request.quantity()) {
            String message = "Insufficient stock for sku '" + request.sku() + "': requested="
                    + request.quantity() + ", available=" + product.getQuantity();
            // No unitPrice on a failed attempt: nothing was reserved, so there is no reserved price.
            saveReservationRecord(request, ReservationStatus.FAILED, message, null);
            throw new InsufficientStockException(message);
        }

        product.setQuantity(product.getQuantity() - request.quantity());
        Product saved = productRepository.save(product); // version bump happens here; throws on conflict

        String message = "Reserved " + request.quantity() + " unit(s) of '" + request.sku() + "'";
        // The price is frozen onto the ledger row, which is what a later replay of this same
        // idempotency key answers with.
        saveReservationRecord(request, ReservationStatus.SUCCESS, message, saved.getPrice());

        if (saved.isBelowThreshold()) {
            eventPublisher.publishLowStock(saved);
        }

        log.info("Reserved stock sku={} quantity={} remaining={}", request.sku(), request.quantity(), saved.getQuantity());
        return new StockReservationResponse(true, saved.getSku(), saved.getQuantity(), message, saved.getPrice());
    }

    private void saveReservationRecord(StockReservationRequest request, ReservationStatus status, String message,
                                       BigDecimal unitPrice) {
        StockReservation record = StockReservation.builder()
                .idempotencyKey(request.idempotencyKey())
                .sku(request.sku())
                .quantity(request.quantity())
                .status(status)
                .message(message)
                .unitPrice(unitPrice)
                .createdAt(Instant.now())
                .build();
        reservationRepository.save(record);
    }
}
