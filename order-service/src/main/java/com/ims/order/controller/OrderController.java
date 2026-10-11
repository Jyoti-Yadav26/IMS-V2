package com.ims.order.controller;

import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;
import com.ims.order.service.OrderService;
import com.ims.order.service.impl.IdempotentOrderCreator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final IdempotentOrderCreator orderCreator;

    /**
     * Always returns 201 with the persisted order, even when reservation ultimately
     * failed - the request to *place* an order succeeded (it was durably recorded), the
     * order's {@code status}/{@code failureReason} fields communicate the outcome. This
     * avoids conflating "did we accept and record this order" with "could the business
     * actually fulfill it right now".
     * <p>
     * The optional {@code Idempotency-Key} header makes a retried or double-clicked request safe: the
     * same key always yields the order that key already created. A replay answers 201 as well, so the
     * caller sees one unchanging response for one key rather than having to treat the repeat specially.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderCreator.create(request, idempotencyKey));
    }

    @GetMapping("/{orderNumber}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderNumber) {
        return ResponseEntity.ok(orderService.getOrder(orderNumber));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }
}
