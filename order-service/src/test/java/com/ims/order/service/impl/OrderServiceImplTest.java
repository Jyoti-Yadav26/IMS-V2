package com.ims.order.service.impl;

import com.ims.order.client.InventoryClientAdapter;
import com.ims.order.client.dto.StockReservationRequest;
import com.ims.order.client.dto.StockReservationResponse;
import com.ims.order.dto.OrderItemRequest;
import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;
import com.ims.order.entity.Order;
import com.ims.order.entity.OrderStatus;
import com.ims.order.event.OrderEventPublisher;
import com.ims.order.exception.DuplicateSkuException;
import com.ims.order.exception.InsufficientStockException;
import com.ims.order.exception.InventoryServiceUnavailableException;
import com.ims.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryClientAdapter inventoryClientAdapter;
    @Mock
    private OrderEventPublisher eventPublisher;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static final String ORDER_NUMBER = "ORD-TEST-1";

    private OrderRequest twoItemRequest;

    @BeforeEach
    void setUp() {
        // save() just returns whatever is passed, mimicking a real JPA save for a
        // detached-then-re-attached entity in these unit tests. It also assigns an order number
        // the way the entity's @PrePersist hook would, since no persistence provider runs here.
        // lenient() because the validation test rejects the request before any save happens.
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getOrderNumber() == null) {
                order.setOrderNumber(ORDER_NUMBER);
            }
            return order;
        });

        twoItemRequest = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-1", 2, BigDecimal.valueOf(10)),
                new OrderItemRequest("SKU-2", 1, BigDecimal.valueOf(20))
        ));
    }

    @Test
    void createOrder_confirmsWhenAllItemsReserved() {
        when(inventoryClientAdapter.reserveStock(any())).thenReturn(new StockReservationResponse(true, "SKU", 5, "ok"));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.totalAmount()).isEqualTo(BigDecimal.valueOf(40));
        verify(inventoryClientAdapter, times(2)).reserveStock(any());
        verify(inventoryClientAdapter, never()).restock(any(), anyInt());
        verify(eventPublisher).publish(any(Order.class));
    }

    @Test
    void createOrder_rejectsAndCompensatesWhenSecondItemHasInsufficientStock() {
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenReturn(new StockReservationResponse(true, "SKU-1", 5, "ok"));
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-2"))))
                .thenThrow(new InsufficientStockException("not enough SKU-2"));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).contains("SKU-2");
        // SKU-1 was reserved before SKU-2 failed, so it must be released...
        verify(inventoryClientAdapter).restock("SKU-1", 2);
        // ...but SKU-2 was never actually reserved, so there is nothing to compensate for it.
        verify(inventoryClientAdapter, never()).restock(eq("SKU-2"), anyInt());
    }

    @Test
    void createOrder_marksFailedAndCompensatesWhenInventoryServiceUnavailable() {
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenReturn(new StockReservationResponse(true, "SKU-1", 5, "ok"));
        when(inventoryClientAdapter.reserveStock(argThat((StockReservationRequest r) -> r != null && r.sku().equals("SKU-2"))))
                .thenThrow(new InventoryServiceUnavailableException("inventory-service is down", new RuntimeException()));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.FAILED);
        verify(inventoryClientAdapter).restock("SKU-1", 2);
    }

    @Test
    void createOrder_persistsPendingOrderBeforeAttemptingReservation() {
        when(inventoryClientAdapter.reserveStock(any())).thenReturn(new StockReservationResponse(true, "SKU", 5, "ok"));

        orderService.createOrder(twoItemRequest);

        verify(orderRepository, times(2)).save(any(Order.class)); // once PENDING, once with final status
    }

    @Test
    void createOrder_usesLineItemIndexAsReservationIdempotencyKey() {
        when(inventoryClientAdapter.reserveStock(any())).thenReturn(new StockReservationResponse(true, "SKU", 5, "ok"));

        orderService.createOrder(twoItemRequest);

        ArgumentCaptor<StockReservationRequest> captor = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(inventoryClientAdapter, times(2)).reserveStock(captor.capture());
        // Keys are per line-item position, not per SKU, so two lines can never share one key.
        assertThat(captor.getAllValues()).extracting(StockReservationRequest::idempotencyKey)
                .containsExactly(ORDER_NUMBER + "-0", ORDER_NUMBER + "-1");
    }

    @Test
    void createOrder_rejectsDuplicateSkuInSameOrder() {
        OrderRequest duplicateSkuRequest = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-1", 2, BigDecimal.valueOf(10)),
                new OrderItemRequest("SKU-1", 3, BigDecimal.valueOf(10))
        ));

        assertThatThrownBy(() -> orderService.createOrder(duplicateSkuRequest))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessageContaining("SKU-1");

        // Rejected before anything happened: no order row, no reservation, nothing to compensate.
        verify(orderRepository, never()).save(any(Order.class));
        verify(inventoryClientAdapter, never()).reserveStock(any());
        verify(inventoryClientAdapter, never()).restock(any(), anyInt());
    }
}
