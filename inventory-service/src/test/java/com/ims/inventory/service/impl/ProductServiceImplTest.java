package com.ims.inventory.service.impl;

import com.ims.inventory.dto.ProductRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockReservationRepository reservationRepository;
    @Mock
    private StockReservationExecutor reservationExecutor;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.builder()
                .id(1L)
                .sku("SKU-1")
                .name("Widget")
                .price(BigDecimal.TEN)
                .quantity(10)
                .reorderThreshold(2)
                .version(0L)
                .build();
    }

    @Test
    void createProduct_throwsWhenSkuAlreadyExists() {
        when(productRepository.existsBySku("SKU-1")).thenReturn(true);

        ProductRequest request = new ProductRequest("SKU-1", "Widget", "desc", BigDecimal.TEN, 10, 2);

        assertThatThrownBy(() -> productService.createProduct(request))
                .isInstanceOf(DuplicateSkuException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void getProduct_throwsWhenNotFound() {
        when(productRepository.findBySku("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct("UNKNOWN"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void reserveStock_replaysStoredResultForKnownIdempotencyKey() {
        StockReservationRequest request = new StockReservationRequest("SKU-1", 3, "key-123");
        StockReservation prior = StockReservation.builder()
                .sku("SKU-1").quantity(3).status(ReservationStatus.SUCCESS).message("Reserved 3 unit(s)").build();
        when(reservationRepository.findByIdempotencyKey("key-123")).thenReturn(Optional.of(prior));
        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product));

        StockReservationResponse response = productService.reserveStock(request);

        assertThat(response.reserved()).isTrue();
        assertThat(response.remainingQuantity()).isEqualTo(10);
        verify(reservationExecutor, never()).attemptReservation(any());
    }

    @Test
    void reserveStock_replayReturnsPriceStoredAtReservationTime() {
        // The product now costs 10, but when this key was reserved it cost 8. A replay has to answer
        // with what was actually reserved: the retry of a call must not be able to reprice it, or an
        // order placed at 8 could be charged at 10 simply because it was retried.
        StockReservationRequest request = new StockReservationRequest("SKU-1", 3, "key-priced");
        StockReservation prior = StockReservation.builder()
                .idempotencyKey("key-priced")
                .sku("SKU-1").quantity(3).status(ReservationStatus.SUCCESS)
                .message("Reserved 3 unit(s)")
                .unitPrice(new BigDecimal("8.00"))
                .build();
        when(reservationRepository.findByIdempotencyKey("key-priced")).thenReturn(Optional.of(prior));
        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product));

        StockReservationResponse response = productService.reserveStock(request);

        assertThat(response.unitPrice()).isEqualByComparingTo("8.00");
        // Current stock is still reported as current - only the price is frozen.
        assertThat(response.remainingQuantity()).isEqualTo(10);
        verify(reservationExecutor, never()).attemptReservation(any());
    }

    @Test
    void reserveStock_delegatesToExecutorForNewRequest() {
        StockReservationRequest request = new StockReservationRequest("SKU-1", 3, "key-new");
        when(reservationRepository.findByIdempotencyKey("key-new")).thenReturn(Optional.empty());
        StockReservationResponse expected = new StockReservationResponse(true, "SKU-1", 7, "Reserved 3 unit(s)", BigDecimal.TEN);
        when(reservationExecutor.attemptReservation(request)).thenReturn(expected);

        StockReservationResponse response = productService.reserveStock(request);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void reserveStock_retriesOnOptimisticLockConflictThenSucceeds() {
        StockReservationRequest request = new StockReservationRequest("SKU-1", 3, "key-retry");
        when(reservationRepository.findByIdempotencyKey("key-retry")).thenReturn(Optional.empty());
        StockReservationResponse expected = new StockReservationResponse(true, "SKU-1", 7, "Reserved 3 unit(s)", BigDecimal.TEN);
        when(reservationExecutor.attemptReservation(request))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L))
                .thenReturn(expected);

        StockReservationResponse response = productService.reserveStock(request);

        assertThat(response).isEqualTo(expected);
        verify(reservationExecutor, times(2)).attemptReservation(request);
    }

    @Test
    void reserveStock_exhaustsRetriesAndThrows() {
        StockReservationRequest request = new StockReservationRequest("SKU-1", 3, "key-conflict");
        when(reservationRepository.findByIdempotencyKey("key-conflict")).thenReturn(Optional.empty());
        when(reservationExecutor.attemptReservation(request))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L));

        assertThatThrownBy(() -> productService.reserveStock(request))
                .isInstanceOf(ConcurrentStockUpdateException.class);

        verify(reservationExecutor, times(3)).attemptReservation(request);
    }

    @Test
    void reserveStock_replaysAfterConcurrentIdempotencyKeyViolation() {
        StockReservationRequest request = new StockReservationRequest("SKU-1", 3, "key-race");
        StockReservation prior = StockReservation.builder()
                .idempotencyKey("key-race")
                .sku("SKU-1")
                .quantity(3)
                .status(ReservationStatus.SUCCESS)
                .message("Reserved 3 unit(s)")
                .build();
        ConstraintViolationException pgUnique = new ConstraintViolationException(
                "duplicate key", new SQLException("duplicate key value violates unique constraint \"stock_reservations_idempotency_key_key\"", "23505"),
                "stock_reservations_idempotency_key_key");
        when(reservationRepository.findByIdempotencyKey("key-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(prior));
        when(reservationExecutor.attemptReservation(request))
                .thenThrow(new DataIntegrityViolationException("duplicate key", pgUnique));
        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product));

        StockReservationResponse response = productService.reserveStock(request);

        assertThat(response.reserved()).isTrue();
        assertThat(response.remainingQuantity()).isEqualTo(10);
        verify(reservationExecutor, times(1)).attemptReservation(request);
    }
}
