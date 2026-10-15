package com.ims.order.service.impl;

import com.ims.order.client.InventoryClientAdapter;
import com.ims.order.client.dto.StockReservationRequest;
import com.ims.order.client.dto.StockReservationResponse;
import com.ims.order.dto.OrderItemRequest;
import com.ims.order.dto.OrderItemResponse;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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

    /** What inventory charges for each SKU in these tests - deliberately different from any price a request carries. */
    private static final Map<String, BigDecimal> INVENTORY_PRICES = Map.of(
            "SKU-1", new BigDecimal("10.00"),
            "SKU-2", new BigDecimal("20.00"));

    /** A successful reservation answered with inventory's own price for the requested sku. */
    private static StockReservationResponse reservedAt(StockReservationRequest request) {
        return new StockReservationResponse(true, request.sku(), 5, "ok", INVENTORY_PRICES.get(request.sku()));
    }

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
        // Prices come from inventory's answer, so the expected 40 below is 2x10 (SKU-1) + 1x20 (SKU-2)
        // as priced by inventory - it is no longer the client's arithmetic.
        when(inventoryClientAdapter.reserveStock(any())).thenAnswer(inv -> reservedAt(inv.getArgument(0)));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.totalAmount()).isEqualByComparingTo("40.00");
        verify(inventoryClientAdapter, times(2)).reserveStock(any());
        verify(inventoryClientAdapter, never()).restock(any(), anyInt());
        verify(eventPublisher).publish(any(Order.class));
    }

    @Test
    void createOrder_ignoresClientUnitPriceAndUsesInventoryPrice() {
        // The client claims every line is free. Inventory says SKU-1 is 12.50 and SKU-2 is 20.00, and
        // inventory is the only price anyone is allowed to believe: 2x12.50 + 1x20.00 = 45.00.
        OrderRequest underpricedRequest = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-1", 2, BigDecimal.ZERO),
                new OrderItemRequest("SKU-2", 1, BigDecimal.ZERO)
        ));
        when(inventoryClientAdapter.reserveStock(any())).thenAnswer(inv -> {
            StockReservationRequest request = inv.getArgument(0);
            BigDecimal price = request.sku().equals("SKU-1") ? new BigDecimal("12.50") : new BigDecimal("20.00");
            return new StockReservationResponse(true, request.sku(), 5, "ok", price);
        });

        OrderResponse response = orderService.createOrder(underpricedRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.totalAmount()).isEqualByComparingTo("45.00");
        assertThat(response.items()).extracting(OrderItemResponse::unitPrice)
                .containsExactly(new BigDecimal("12.50"), new BigDecimal("20.00"));
    }

    @Test
    void createOrder_acceptsRequestWithoutUnitPrice() {
        // unitPrice is optional now, because it is ignored anyway. A client that omits it entirely
        // must still get a correctly priced order rather than a 400 or an NPE.
        OrderRequest pricelessRequest = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-1", 2, null),
                new OrderItemRequest("SKU-2", 1, null)
        ));
        when(inventoryClientAdapter.reserveStock(any())).thenAnswer(inv -> reservedAt(inv.getArgument(0)));

        OrderResponse response = orderService.createOrder(pricelessRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.totalAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void createOrder_sumsDifferentPricesAcrossItems() {
        OrderRequest request = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-1", 3, BigDecimal.ONE),
                new OrderItemRequest("SKU-2", 2, BigDecimal.ONE)
        ));
        when(inventoryClientAdapter.reserveStock(any())).thenAnswer(inv -> {
            StockReservationRequest reservation = inv.getArgument(0);
            BigDecimal price = reservation.sku().equals("SKU-1") ? new BigDecimal("1.05") : new BigDecimal("0.95");
            return new StockReservationResponse(true, reservation.sku(), 5, "ok", price);
        });

        OrderResponse response = orderService.createOrder(request);

        // 3 x 1.05 + 2 x 0.95
        assertThat(response.totalAmount()).isEqualByComparingTo("5.05");
    }

    @Test
    void createOrder_failsWhenInventoryReservesWithoutUnitPrice() {
        // A reserved line with no price is a broken contract (most likely a version skew), not a
        // business rejection: inventory answered, it just answered unusably. Falling back to the
        // client's price is exactly the hole we are closing, so the order FAILS instead.
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenAnswer(inv -> reservedAt(inv.getArgument(0)));
        when(inventoryClientAdapter.reserveStock(argThat((StockReservationRequest r) -> r != null && r.sku().equals("SKU-2"))))
                .thenReturn(new StockReservationResponse(true, "SKU-2", 5, "ok", null));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(response.failureReason()).contains("SKU-2").contains("unitPrice");
        assertThat(response.totalAmount()).isEqualByComparingTo("0");
        // SKU-2's stock really was decremented, so it has to be released too - not just SKU-1.
        verify(inventoryClientAdapter).restock("SKU-1", 2);
        verify(inventoryClientAdapter).restock("SKU-2", 1);
    }

    @Test
    void createOrder_rejectsWhenInventoryReportsNotReserved() {
        // reserved=false is inventory saying no, which is a REJECTED order. It is how a replayed
        // FAILED reservation answers (200 + reserved=false rather than a 409).
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenAnswer(inv -> reservedAt(inv.getArgument(0)));
        when(inventoryClientAdapter.reserveStock(argThat((StockReservationRequest r) -> r != null && r.sku().equals("SKU-2"))))
                .thenReturn(new StockReservationResponse(false, "SKU-2", null, "Insufficient stock for sku 'SKU-2'", null));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).isEqualTo("Insufficient stock for sku 'SKU-2'");
        assertThat(response.totalAmount()).isEqualByComparingTo("0");
        verify(inventoryClientAdapter).restock("SKU-1", 2);
        // Nothing was decremented for SKU-2, so restocking it would invent stock out of nothing.
        verify(inventoryClientAdapter, never()).restock(eq("SKU-2"), anyInt());
    }

    @Test
    void createOrder_zeroesTotalOnRejectedOrderButKeepsThePricesItWasGiven() {
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenAnswer(inv -> reservedAt(inv.getArgument(0)));
        when(inventoryClientAdapter.reserveStock(argThat((StockReservationRequest r) -> r != null && r.sku().equals("SKU-2"))))
                .thenThrow(new InsufficientStockException("not enough SKU-2"));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        // Nothing is owed on a rejected order - its stock was released - so the total is zero rather
        // than a part-sum that reads like an amount due.
        assertThat(response.totalAmount()).isEqualByComparingTo("0");
        // The price inventory did quote is still on the line it quoted it for, as an audit trail.
        assertThat(response.items()).extracting(OrderItemResponse::sku, OrderItemResponse::unitPrice)
                .containsExactly(tuple("SKU-1", new BigDecimal("10.00")), tuple("SKU-2", null));
    }

    @Test
    void createOrder_rejectsAndCompensatesWhenSecondItemHasInsufficientStock() {
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenReturn(new StockReservationResponse(true, "SKU-1", 5, "ok", BigDecimal.TEN));
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
                .thenReturn(new StockReservationResponse(true, "SKU-1", 5, "ok", BigDecimal.TEN));
        when(inventoryClientAdapter.reserveStock(argThat((StockReservationRequest r) -> r != null && r.sku().equals("SKU-2"))))
                .thenThrow(new InventoryServiceUnavailableException("inventory-service is down", new RuntimeException()));

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.FAILED);
        verify(inventoryClientAdapter).restock("SKU-1", 2);
    }

    @Test
    void createOrder_leavesItemReservedWhenCompensatingRestockFails() {
        // SKU-1 reserves, SKU-2 is rejected, so compensation tries to release SKU-1 - and that
        // release fails too (inventory-service down). The stock is therefore still decremented
        // downstream, so the order must not claim the line was released.
        when(inventoryClientAdapter.reserveStock(argThat(r -> r != null && r.sku().equals("SKU-1"))))
                .thenReturn(new StockReservationResponse(true, "SKU-1", 5, "ok", BigDecimal.TEN));
        when(inventoryClientAdapter.reserveStock(argThat((StockReservationRequest r) -> r != null && r.sku().equals("SKU-2"))))
                .thenThrow(new InsufficientStockException("not enough SKU-2"));
        doThrow(new InventoryServiceUnavailableException("inventory-service is down", new RuntimeException()))
                .when(inventoryClientAdapter).restock("SKU-1", 2);

        OrderResponse response = orderService.createOrder(twoItemRequest);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.failureReason()).contains("SKU-2");
        verify(inventoryClientAdapter).restock("SKU-1", 2);
        // SKU-1 stays reserved because the release failed; SKU-2 was never reserved at all.
        assertThat(response.items()).extracting(OrderItemResponse::sku, OrderItemResponse::reserved)
                .containsExactly(tuple("SKU-1", true), tuple("SKU-2", false));
    }

    @Test
    void createOrder_persistsPendingOrderBeforeAttemptingReservation() {
        when(inventoryClientAdapter.reserveStock(any()))
                .thenReturn(new StockReservationResponse(true, "SKU", 5, "ok", BigDecimal.TEN));

        orderService.createOrder(twoItemRequest);

        verify(orderRepository, times(2)).save(any(Order.class)); // once PENDING, once with final status
    }

    @Test
    void createOrder_usesLineItemIndexAsReservationIdempotencyKey() {
        when(inventoryClientAdapter.reserveStock(any()))
                .thenReturn(new StockReservationResponse(true, "SKU", 5, "ok", BigDecimal.TEN));

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

    @Test
    void createOrder_replaysStoredOrderWhenIdempotencyKeyAlreadyUsed() {
        // The same key was already used by an earlier request, so this one must hand back that order
        // untouched - a double click or a client retry cannot place a second order or reserve again.
        Order stored = Order.builder()
                .orderNumber("ORD-STORED")
                .customerEmail("buyer@example.com")
                .status(OrderStatus.CONFIRMED)
                .totalAmount(BigDecimal.valueOf(40))
                .idempotencyKey("KEY-1")
                .build();
        when(orderRepository.findByIdempotencyKey("KEY-1")).thenReturn(Optional.of(stored));

        OrderResponse response = orderService.createOrder(twoItemRequest, "KEY-1");

        assertThat(response.orderNumber()).isEqualTo("ORD-STORED");
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).save(any(Order.class));
        verify(inventoryClientAdapter, never()).reserveStock(any());
        verify(eventPublisher, never()).publish(any(Order.class));
    }

    @Test
    void createOrder_storesIdempotencyKeyOnTheNewOrder() {
        when(inventoryClientAdapter.reserveStock(any()))
                .thenReturn(new StockReservationResponse(true, "SKU", 5, "ok", BigDecimal.TEN));

        orderService.createOrder(twoItemRequest, "KEY-1");

        // Persisted on the order row itself, under a unique constraint - that row is what makes the
        // key durable across instances and restarts, rather than anything held in memory.
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("KEY-1");
    }

    @Test
    void createOrder_treatsBlankIdempotencyKeyAsAbsent() {
        when(inventoryClientAdapter.reserveStock(any()))
                .thenReturn(new StockReservationResponse(true, "SKU", 5, "ok", BigDecimal.TEN));

        OrderResponse response = orderService.createOrder(twoItemRequest, "   ");

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        // A blank header is no key at all: it is never looked up, and it is stored as null so two
        // blank-keyed orders can never collide on the unique constraint.
        verify(orderRepository, never()).findByIdempotencyKey(any());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isNull();
    }
}
