package com.ims.order.service;

import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;

import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request, String idempotencyKey);

    /** A request that carried no {@code Idempotency-Key} header. */
    default OrderResponse createOrder(OrderRequest request) {
        return createOrder(request, null);
    }

    OrderResponse getOrder(String orderNumber);

    List<OrderResponse> getAllOrders();
}
