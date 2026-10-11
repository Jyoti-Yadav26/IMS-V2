package com.ims.order.service.impl;

import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;
import com.ims.order.repository.OrderRepository;
import com.ims.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Entry point for placing an order, wrapping {@link OrderService#createOrder} with the losing half of
 * the {@code Idempotency-Key} race.
 * <p>
 * {@code createOrder} checks for an existing key first, but two concurrent requests can both get past
 * that check; the unique constraint on {@code orders.idempotency_key} then rejects the second INSERT.
 * That rejection cannot be handled inside {@code createOrder}: the whole saga runs in one transaction,
 * {@code Order}'s id is IDENTITY-generated so the INSERT (and therefore the violation) happens during
 * {@code save}, and JPA marks the transaction rollback-only - a read afterwards is unreliable and the
 * commit would fail with {@code UnexpectedRollbackException}. So the catch-and-re-read has to run
 * after that transaction has ended, which means a separate, non-transactional bean: calling a
 * {@code @Transactional} method on {@code this} would bypass the proxy and silently run the entire
 * saga with no transaction at all. Same proxy reasoning as
 * {@code inventory-service}'s {@code StockReservationExecutor}, applied from the outside in.
 * <p>
 * Postgres blocks the duplicate INSERT until the first request's transaction finishes, so by the time
 * the violation surfaces the winning order is committed and readable. The cost is that the duplicate
 * request waits out the winner's whole saga before getting its answer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotentOrderCreator {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderResponse create(OrderRequest request, String idempotencyKey) {
        try {
            return orderService.createOrder(request, idempotencyKey);
        } catch (DataIntegrityViolationException ex) {
            if (!StringUtils.hasText(idempotencyKey) || !isIdempotencyKeyViolation(ex)) {
                throw ex;
            }
            log.info("Concurrent duplicate Idempotency-Key {}, returning the order that won the race", idempotencyKey);
            return orderRepository.findByIdempotencyKey(idempotencyKey)
                    .map(OrderResponse::from)
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Only a collision on the idempotency key is a replay; every other integrity violation (a
     * duplicate order number, a null column) is a real failure and must keep propagating. Copied from
     * {@code inventory-service}'s {@code ProductServiceImpl}: match SQLState 23505 plus the constraint
     * name, and fall back to the message because the driver's {@code SQLException} is usually the most
     * specific cause. The constraint is named {@code uk_orders_idempotency_key} so the name check works
     * on Postgres and H2 alike.
     */
    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof ConstraintViolationException constraintEx) {
            if (!"23505".equals(constraintEx.getSQLState())) {
                return false;
            }
            String constraint = constraintEx.getConstraintName();
            return constraint != null && constraint.toLowerCase().contains("idempotency");
        }
        String message = cause != null ? cause.getMessage() : ex.getMessage();
        return message != null && message.toLowerCase().contains("idempotency");
    }
}
