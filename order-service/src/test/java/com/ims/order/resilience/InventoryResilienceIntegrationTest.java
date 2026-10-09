package com.ims.order.resilience;

import com.ims.order.dto.OrderItemRequest;
import com.ims.order.dto.OrderItemResponse;
import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;
import com.ims.order.entity.OrderStatus;
import com.ims.order.event.OrderEventPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Exercises the real Resilience4j @CircuitBreaker/@Retry stack on InventoryClientAdapter
 * against WireMock standing in for inventory-service (registered as the "inventory-service"
 * instance via Spring Cloud's simple discovery client, so the normal Eureka-resolved,
 * load-balanced Feign path is exercised unchanged - just against a different discovery
 * backend). Eureka itself stays disabled per the base test application.yml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("resilience-it")
@AutoConfigureWireMock(port = 0)
class InventoryResilienceIntegrationTest {

    private static final String RESERVE_URL = "/api/inventory/reserve";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockBean
    private OrderEventPublisher eventPublisher;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        reset();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventoryService");
        circuitBreaker.reset();
    }

    @Test
    void insufficientStock_rejectsWithExactlyOneCall() {
        stubFor(post(urlEqualTo(RESERVE_URL))
                .willReturn(aResponse().withStatus(409).withBody("Insufficient stock for sku 'SKU-1'")));

        ResponseEntity<OrderResponse> response = postOrder("SKU-1");

        assertThat(response.getBody().status()).isEqualTo(OrderStatus.REJECTED);
        verify(1, postRequestedFor(urlEqualTo(RESERVE_URL)));
    }

    @Test
    void inventoryError_retriesThreeTimesThenFails() {
        stubFor(post(urlEqualTo(RESERVE_URL)).willReturn(aResponse().withStatus(500)));

        ResponseEntity<OrderResponse> response = postOrder("SKU-2");

        assertThat(response.getBody().status()).isEqualTo(OrderStatus.FAILED);
        verify(3, postRequestedFor(urlEqualTo(RESERVE_URL)));
    }

    @Test
    void circuitOpen_failsFastWithZeroCalls() {
        stubFor(post(urlEqualTo(RESERVE_URL)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"reserved\":true,\"sku\":\"SKU-3\",\"remainingQuantity\":5,\"message\":\"ok\"}")));

        // Warm-up call while the circuit is still closed, so JVM/HTTP-client/connection
        // setup costs don't bleed into the timing assertion below.
        postOrder("SKU-3");
        resetAllRequests();

        circuitBreaker.transitionToOpenState();

        long start = System.nanoTime();
        ResponseEntity<OrderResponse> response = postOrder("SKU-3");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getBody().status()).isEqualTo(OrderStatus.FAILED);
        assertThat(elapsedMs).isLessThan(100);
        verify(0, postRequestedFor(urlEqualTo(RESERVE_URL)));
    }

    @Test
    void restockFailure_compensationCallsOnlyOnce() {
        stubFor(post(urlEqualTo(RESERVE_URL))
                .withRequestBody(matchingJsonPath("$[?(@.sku == 'SKU-4')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reserved\":true,\"sku\":\"SKU-4\",\"remainingQuantity\":5,\"message\":\"ok\"}")));
        stubFor(post(urlEqualTo(RESERVE_URL))
                .withRequestBody(matchingJsonPath("$[?(@.sku == 'SKU-5')]"))
                .willReturn(aResponse().withStatus(409).withBody("Insufficient stock for sku 'SKU-5'")));
        stubFor(post(urlPathMatching("/api/inventory/products/.*/restock"))
                .willReturn(aResponse().withStatus(500)));

        OrderRequest request = new OrderRequest("buyer@example.com", List.of(
                new OrderItemRequest("SKU-4", 1, BigDecimal.TEN),
                new OrderItemRequest("SKU-5", 1, BigDecimal.TEN)
        ));
        ResponseEntity<OrderResponse> response = restTemplate.postForEntity("/api/orders", request, OrderResponse.class);

        assertThat(response.getBody().status()).isEqualTo(OrderStatus.REJECTED);
        verify(1, postRequestedFor(urlPathMatching("/api/inventory/products/.*/restock")));
        // The restock 500 reaches restockFallback, which throws instead of returning: SKU-4 is
        // still decremented in inventory, so the order has to keep showing it as reserved.
        assertThat(response.getBody().items()).extracting(OrderItemResponse::sku, OrderItemResponse::reserved)
                .containsExactly(tuple("SKU-4", true), tuple("SKU-5", false));
    }

    private ResponseEntity<OrderResponse> postOrder(String sku) {
        OrderRequest request = new OrderRequest("buyer@example.com",
                List.of(new OrderItemRequest(sku, 1, BigDecimal.TEN)));
        return restTemplate.postForEntity("/api/orders", request, OrderResponse.class);
    }
}
