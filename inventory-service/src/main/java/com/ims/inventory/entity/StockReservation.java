package com.ims.inventory.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ledger of every stock-reservation attempt, keyed by the caller-supplied idempotency
 * key. This is what makes {@code POST /reserve} safe to retry: if order-service times
 * out waiting for a response and retries the exact same request, we look the key up
 * here and replay the original outcome instead of decrementing stock a second time.
 */
@Entity
@Table(name = "stock_reservations", uniqueConstraints = @UniqueConstraint(columnNames = "idempotency_key"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    private String message;

    /**
     * The product's price at the moment of the reservation, so an idempotent replay answers with the
     * price that was actually reserved rather than whatever the price happens to be now. Nullable:
     * rows written before this column existed have none, and a FAILED attempt priced nothing.
     */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
