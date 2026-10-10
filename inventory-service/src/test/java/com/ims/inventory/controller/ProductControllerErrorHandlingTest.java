package com.ims.inventory.controller;

import com.ims.inventory.exception.GlobalExceptionHandler;
import com.ims.inventory.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real Spring MVC error path - message converters, {@code @Validated} method
 * validation and {@link GlobalExceptionHandler} - over a mocked service layer. The
 * {@code @Min(1)} on the restock query parameter is enforced by the AOP proxy created by
 * {@code @Validated} on the controller, which throws ConstraintViolationException; that used to
 * land in the catch-all handler and be reported as a 500.
 */
@WebMvcTest(ProductController.class)
class ProductControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    void malformedJsonBody_returns400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"quantity\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages[0]").value("Malformed JSON request body"));

        verify(productService, never()).reserveStock(any());
    }

    @Test
    void minViolationOnQueryParameter_returns400() throws Exception {
        mockMvc.perform(post("/api/inventory/products/SKU-1/restock").param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messages[0]").value(org.hamcrest.Matchers.containsString("quantity")));

        verify(productService, never()).restock(anyString(), anyInt());
    }

    @Test
    void unexpectedFailure_returns500WithAGenericMessageAndNoInternalDetail() throws Exception {
        when(productService.restock(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("could not extract ResultSet from inventory_db.products"));

        mockMvc.perform(post("/api/inventory/products/SKU-1/restock").param("quantity", "5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.messages[0]").value("An unexpected internal error occurred"));
    }
}
