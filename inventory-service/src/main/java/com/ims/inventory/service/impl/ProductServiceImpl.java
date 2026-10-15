package com.ims.inventory.service.impl;

import com.ims.inventory.dto.ProductRequest;
import com.ims.inventory.dto.ProductResponse;
import com.ims.inventory.dto.StockReservationRequest;
import com.ims.inventory.dto.StockReservationResponse;
import com.ims.inventory.entity.Product;
import com.ims.inventory.entity.ReservationStatus;
import com.ims.inventory.entity.StockReservation;
import com.ims.inventory.exception.ConcurrentStockUpdateException;
import com.ims.inventory.exception.DuplicateSkuException;
import com.ims.inventory.exception.ProductNotFoundException;
import com.ims.inventory.repository.ProductRepository;
import com.ims.inventory.repository.StockReservationRepository;
import com.ims.inventory.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;
    private static final int MAX_IDEMPOTENCY_REPLAY_RETRIES = 5;

    private final ProductRepository productRepository;
    private final StockReservationRepository reservationRepository;
    private final StockReservationExecutor reservationExecutor;

    @Override
    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException("Product with sku '" + request.sku() + "' already exists");
        }
        Product product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .quantity(request.quantity())
                .reorderThreshold(request.reorderThreshold())
                .build();
        Product saved = productRepository.save(product);
        log.info("Created product sku={} quantity={}", saved.getSku(), saved.getQuantity());
        return ProductResponse.from(saved);
    }

    @Override
    public ProductResponse getProduct(String sku) {
        return ProductResponse.from(findBySkuOrThrow(sku));
    }

    @Override
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream().map(ProductResponse::from).toList();
    }

    /**
     * Decrements stock for a single SKU. Two things make this safe under concurrency:
     * <ol>
     *   <li>Idempotency: if a reservation with this key already ran, we replay its
     *       stored outcome instead of decrementing again (handles order-service retries
     *       after a timeout where the first attempt actually succeeded server-side).</li>
     *   <li>Optimistic locking: each attempt re-reads the row (with its {@code version}),
     *       and if another transaction updated it first, JPA throws on save; we retry the
     *       read-check-write cycle a bounded number of times rather than losing updates.</li>
     * </ol>
     */
    @Override
    public StockReservationResponse reserveStock(StockReservationRequest request) {
        var existing = reservationRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return buildResponseFromPriorReservation(existing.get());
        }

        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                return reservationExecutor.attemptReservation(request);
            } catch (ObjectOptimisticLockingFailureException ex) {
                log.warn("Optimistic lock conflict reserving sku={} attempt={}/{}", request.sku(), attempt, MAX_OPTIMISTIC_LOCK_RETRIES);
                if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
                    throw new ConcurrentStockUpdateException(
                            "Too much contention updating stock for sku '" + request.sku() + "', please retry");
                }
                sleepBriefly(attempt);
            } catch (DataIntegrityViolationException ex) {
                if (!isIdempotencyKeyViolation(ex)) {
                    throw ex;
                }
                log.info("Concurrent duplicate idempotency key={}, replaying stored result", request.idempotencyKey());
                StockReservationResponse replay = replayAfterIdempotencyConflict(request.idempotencyKey());
                if (replay != null) {
                    return replay;
                }
                if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
                    throw new ConcurrentStockUpdateException(
                            "Duplicate idempotency key '" + request.idempotencyKey() + "' could not be resolved");
                }
                sleepBriefly(attempt);
            }
        }
        throw new ConcurrentStockUpdateException("Unable to reserve stock for sku '" + request.sku() + "'");
    }

    @Override
    @Transactional
    public ProductResponse restock(String sku, int quantity) {
        Product product = findBySkuOrThrow(sku);
        product.setQuantity(product.getQuantity() + quantity);
        Product saved = productRepository.save(product);
        log.info("Restocked sku={} by={} newQuantity={}", sku, quantity, saved.getQuantity());
        return ProductResponse.from(saved);
    }

    private StockReservationResponse buildResponseFromPriorReservation(StockReservation prior) {
        log.info("Replaying idempotent reservation result for key={}", prior.getIdempotencyKey());
        if (prior.getStatus() == ReservationStatus.SUCCESS) {
            Product product = findBySkuOrThrow(prior.getSku());
            // remainingQuantity is current stock (the caller wants to know what is left now), but the
            // price comes off the ledger row, not the product: a replay must answer with the price
            // that was reserved, even if an admin has changed the product's price since.
            return new StockReservationResponse(true, prior.getSku(), product.getQuantity(), prior.getMessage(),
                    prior.getUnitPrice());
        }
        // Nothing was reserved, so there is no reserved price to replay.
        return new StockReservationResponse(false, prior.getSku(), null, prior.getMessage(), null);
    }

    /**
     * After a unique-key collision on {@code idempotency_key}, the winning transaction may
     * still be committing. Poll briefly so we return the same 200 replay the sequential
     * path would have returned, instead of surfacing SQLState 23505 as a 500.
     */
    private StockReservationResponse replayAfterIdempotencyConflict(String idempotencyKey) {
        for (int attempt = 1; attempt <= MAX_IDEMPOTENCY_REPLAY_RETRIES; attempt++) {
            var existing = reservationRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return buildResponseFromPriorReservation(existing.get());
            }
            sleepBriefly(attempt);
        }
        return null;
    }

    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof ConstraintViolationException constraintEx) {
            if (!"23505".equals(constraintEx.getSQLState())) {
                return false;
            }
            String constraint = constraintEx.getConstraintName();
            return constraint != null && constraint.toLowerCase().contains("idempotency");
        }
        String message = cause != null ? cause.getMessage() : ex.getMessage();
        return message != null && message.toLowerCase().contains("idempotency");
    }

    private Product findBySkuOrThrow(String sku) {
        return productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException("Product with sku '" + sku + "' not found"));
    }

    private void sleepBriefly(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
