package com.ims.order.idempotency;

import com.ims.order.client.InventoryClientAdapter;
import com.ims.order.client.dto.StockReservationRequest;
import com.ims.order.client.dto.StockReservationResponse;
import com.ims.order.dto.OrderResponse;
import com.ims.order.entity.Order;
import com.ims.order.entity.OrderStatus;
import com.ims.order.event.OrderEventPublisher;
import com.ims.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real HTTP + JPA stack (H2) with inventory-service and Kafka mocked out, to prove the
 * property the unit tests can only approximate: posting the same {@code Idempotency-Key} twice leaves
 * exactly one order row behind and reserves each line item exactly once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderIdempotencyIntegrationTest {

    private static final String TWO_ITEM_BODY =
            "{\"customerEmail\":\"buyer@example.com\","
                    + "\"items\":[{\"sku\":\"SKU-1\",\"quantity\":2,\"unitPrice\":10},"
                    + "{\"sku\":\"SKU-2\",\"quantity\":1,\"unitPrice\":20}]}";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private InventoryClientAdapter inventoryClientAdapter;

    @MockBean
    private OrderEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        when(inventoryClientAdapter.reserveStock(any())).thenAnswer(invocation -> {
            StockReservationRequest request = invocation.getArgument(0);
            return new StockReservationResponse(true, request.sku(), 5, "Reserved");
        });
    }

    @Test
    void sameIdempotencyKeyTwice_createsOneOrderAndReservesEachItemOnce() {
        ResponseEntity<OrderResponse> first = placeOrder("KEY-1");
        ResponseEntity<OrderResponse> second = placeOrder("KEY-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().orderNumber()).isEqualTo(first.getBody().orderNumber());
        assertThat(second.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);

        assertThat(orderRepository.count()).isEqualTo(1);
        // Two line items, one reservation each - the replay reserved nothing a second time.
        verify(inventoryClientAdapter, times(2)).reserveStock(any());
    }

    @Test
    void withoutAnIdempotencyKey_eachRequestStillPlacesItsOwnOrder() {
        ResponseEntity<OrderResponse> first = placeOrder(null);
        ResponseEntity<OrderResponse> second = placeOrder(null);

        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().orderNumber()).isNotEqualTo(first.getBody().orderNumber());
        assertThat(orderRepository.count()).isEqualTo(2);
        verify(inventoryClientAdapter, times(4)).reserveStock(any());
    }

    @Test
    void theIdempotencyKeyColumnIsUniqueButNullable() {
        // The HTTP path above is short-circuited by the pre-check, so assert the schema directly:
        // the unique constraint is what makes that pre-check safe under concurrency.
        orderRepository.saveAndFlush(orderWithKey("DUP"));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(orderWithKey("DUP")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Key-less orders are unconstrained: NULLs count as distinct.
        orderRepository.saveAndFlush(orderWithKey(null));
        orderRepository.saveAndFlush(orderWithKey(null));
        assertThat(orderRepository.count()).isEqualTo(3);
    }

    private ResponseEntity<OrderResponse> placeOrder(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.postForEntity("/api/orders", new HttpEntity<>(TWO_ITEM_BODY, headers), OrderResponse.class);
    }

    private static Order orderWithKey(String idempotencyKey) {
        return Order.builder()
                .customerEmail("buyer@example.com")
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .idempotencyKey(idempotencyKey)
                .build();
    }
}
