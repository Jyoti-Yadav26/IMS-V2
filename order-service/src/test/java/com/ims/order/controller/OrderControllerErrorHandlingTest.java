package com.ims.order.controller;

import com.ims.order.dto.OrderItemRequest;
import com.ims.order.exception.GlobalExceptionHandler;
import com.ims.order.service.OrderService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real Spring MVC error path - message converters, bean validation and
 * {@link GlobalExceptionHandler} - over a mocked service layer, to pin which HTTP status each
 * class of caller mistake produces. Malformed JSON and parameter constraint violations used to
 * fall through to the catch-all handler and surface as 500s.
 */
@WebMvcTest(OrderController.class)
class OrderControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    void malformedJsonBody_returns400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\": \"buyer@example.com\", \"items\": ["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages[0]").value("Malformed JSON request body"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void malformedJsonResponse_doesNotLeakParserInternals() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andExpect(status().isBadRequest())
                // No Jackson class names, no echo of the rejected input.
                .andExpect(jsonPath("$.messages[0]").value("Malformed JSON request body"));
    }

    @Test
    void beanValidationOnRequestBody_returns400WithFieldMessage() throws Exception {
        String bodyWithZeroQuantity = """
                {"customerEmail":"buyer@example.com",
                 "items":[{"sku":"SKU-1","quantity":0,"unitPrice":10}]}""";

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(bodyWithZeroQuantity))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value("items[0].quantity: quantity must be >= 1"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void constraintViolationFromTheServiceLayer_returns400WithThePropertyPath() throws Exception {
        when(orderService.createOrder(any())).thenThrow(new ConstraintViolationException(realViolations()));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages[0]").value("quantity: quantity must be >= 1"));
    }

    @Test
    void unexpectedFailure_returns500WithAGenericMessageAndNoInternalDetail() throws Exception {
        when(orderService.createOrder(any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://db-prod-1:5432/order_db connection refused"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messages[0]").value("An unexpected internal error occurred"));
    }

    private static String validBody() {
        return """
                {"customerEmail":"buyer@example.com",
                 "items":[{"sku":"SKU-1","quantity":1,"unitPrice":10}]}""";
    }

    /** Real violations rather than a hand-built set, so the property path matches what Hibernate Validator produces. */
    private static Set<ConstraintViolation<OrderItemRequest>> realViolations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(new OrderItemRequest("SKU-1", 0, BigDecimal.TEN));
        }
    }
}
