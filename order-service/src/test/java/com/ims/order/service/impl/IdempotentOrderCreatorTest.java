package com.ims.order.service.impl;

import com.ims.order.dto.OrderItemRequest;
import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;
import com.ims.order.entity.Order;
import com.ims.order.entity.OrderStatus;
import com.ims.order.repository.OrderRepository;
import com.ims.order.service.OrderService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the losing half of the {@code Idempotency-Key} race: two requests get past the pre-check in
 * {@link OrderServiceImpl#createOrder} at the same time, so the second INSERT is rejected by the
 * unique constraint. The violation is fabricated the way Postgres reports it (SQLState 23505 plus the
 * constraint name), the same approach inventory-service's {@code ProductServiceImplTest} uses, because
 * provoking a real cross-transaction collision in a unit test is not worth the machinery.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentOrderCreatorTest {

    @Mock
    private OrderService orderService;
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private IdempotentOrderCreator orderCreator;

    private OrderRequest request;

    @BeforeEach
    void setUp() {
        request = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-1", 2, BigDecimal.valueOf(10))
        ));
    }

    @Test
    void create_returnsTheWinningOrderWhenAConcurrentRequestInsertedTheSameKey() {
        when(orderService.createOrder(request, "KEY-1")).thenThrow(uniqueViolation("uk_orders_idempotency_key"));
        when(orderRepository.findByIdempotencyKey("KEY-1")).thenReturn(Optional.of(winningOrder()));

        OrderResponse response = orderCreator.create(request, "KEY-1");

        // One key, one order: the duplicate request answers with whatever the winner produced.
        assertThat(response.orderNumber()).isEqualTo("ORD-WINNER");
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void create_rethrowsIntegrityViolationsOnOtherConstraints() {
        // A collision on something else is a real failure, not a replay - swallowing it would hide bugs.
        DataIntegrityViolationException unrelated = uniqueViolation("orders_order_number_key");
        when(orderService.createOrder(request, "KEY-1")).thenThrow(unrelated);

        assertThatThrownBy(() -> orderCreator.create(request, "KEY-1")).isSameAs(unrelated);

        verifyNoInteractions(orderRepository);
    }

    @Test
    void create_rethrowsWhenTheKeyCollidedButNoOrderCanBeFound() {
        // Should not happen (the duplicate INSERT blocks until the winner commits), so if it does the
        // caller must see the failure rather than a silent null or an empty 201.
        DataIntegrityViolationException violation = uniqueViolation("uk_orders_idempotency_key");
        when(orderService.createOrder(request, "KEY-1")).thenThrow(violation);
        when(orderRepository.findByIdempotencyKey("KEY-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderCreator.create(request, "KEY-1")).isSameAs(violation);
    }

    @Test
    void create_delegatesWithoutTouchingTheRepositoryOnTheHappyPath() {
        OrderResponse created = OrderResponse.from(winningOrder());
        when(orderService.createOrder(request, "KEY-1")).thenReturn(created);

        assertThat(orderCreator.create(request, "KEY-1")).isSameAs(created);

        verify(orderService).createOrder(request, "KEY-1");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void create_passesAMissingKeyStraightThrough() {
        OrderResponse created = OrderResponse.from(winningOrder());
        when(orderService.createOrder(any(), eq((String) null))).thenReturn(created);

        assertThat(orderCreator.create(request, null)).isSameAs(created);

        verify(orderRepository, never()).findByIdempotencyKey(any());
    }

    private static Order winningOrder() {
        return Order.builder()
                .orderNumber("ORD-WINNER")
                .customerEmail("buyer@example.com")
                .status(OrderStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(20))
                .idempotencyKey("KEY-1")
                .build();
    }

    /** Shaped like a real Postgres unique-constraint failure as Spring Data surfaces it. */
    private static DataIntegrityViolationException uniqueViolation(String constraintName) {
        ConstraintViolationException pgUnique = new ConstraintViolationException(
                "duplicate key",
                new SQLException("duplicate key value violates unique constraint \"" + constraintName + "\"", "23505"),
                constraintName);
        return new DataIntegrityViolationException("duplicate key", pgUnique);
    }
}
