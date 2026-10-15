package com.ims.order.service.impl;

import com.ims.order.client.InventoryClientAdapter;
import com.ims.order.client.dto.StockReservationRequest;
import com.ims.order.client.dto.StockReservationResponse;
import com.ims.order.dto.OrderItemRequest;
import com.ims.order.dto.OrderRequest;
import com.ims.order.dto.OrderResponse;
import com.ims.order.entity.Order;
import com.ims.order.entity.OrderItem;
import com.ims.order.entity.OrderStatus;
import com.ims.order.event.OrderEventPublisher;
import com.ims.order.exception.DuplicateSkuException;
import com.ims.order.exception.InsufficientStockException;
import com.ims.order.exception.InvalidInventoryResponseException;
import com.ims.order.exception.InventoryServiceUnavailableException;
import com.ims.order.exception.OrderNotFoundException;
import com.ims.order.exception.ProductNotFoundException;
import com.ims.order.repository.OrderRepository;
import com.ims.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final InventoryClientAdapter inventoryClientAdapter;
    private final OrderEventPublisher eventPublisher;

    /**
     * Places an order and synchronously reserves stock for each line item.
     * <p>
     * There is no distributed transaction spanning order-service and inventory-service.
     * Instead this method runs a manual saga: reserve items one at a time, and if any
     * item fails (business rejection or inventory-service being down), it compensates by
     * releasing (restocking) whichever earlier items in the same order were already
     * reserved, then records the order as REJECTED or FAILED - it never leaves inventory
     * permanently decremented for an order that didn't go through.
     * <p>
     * The order row itself is persisted up front in PENDING before any remote calls, so
     * there's always a durable audit trail of the attempt even if this process crashes
     * mid-reservation.
     * <p>
     * A request that repeats a SKU across line items is rejected up front (400) rather than
     * reserved, because the two lines would share one reservation idempotency key.
     * <p>
     * Prices are not the caller's to decide. Each line is priced by inventory-service as part of
     * reserving it, and {@code totalAmount} is summed from those prices just before the final save -
     * any {@code unitPrice} in the request is ignored outright. Only a CONFIRMED order carries a
     * total; a REJECTED or FAILED one is zero, because its reserved stock was released and nothing
     * is owed.
     * <p>
     * When the caller supplied an {@code Idempotency-Key} and an order already carries it, that stored
     * order is returned and nothing is reserved again - a double-clicked or retried request must not
     * place a second order. The key is held under a unique constraint on {@code orders}, so this
     * pre-check is only the fast path; the race where two requests get past it at the same time is
     * resolved by {@link IdempotentOrderCreator}. The key is compared exactly and the request body is
     * deliberately not fingerprinted, so re-using a key with a different body replays the first order.
     */
    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request, String idempotencyKey) {
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey : null;
        if (key != null) {
            Optional<Order> replay = orderRepository.findByIdempotencyKey(key);
            if (replay.isPresent()) {
                log.info("Replaying order {} for Idempotency-Key {}", replay.get().getOrderNumber(), key);
                return OrderResponse.from(replay.get());
            }
        }

        validateNoDuplicateSkus(request);

        Order order = buildPendingOrder(request);
        order.setIdempotencyKey(key);
        order = orderRepository.save(order);

        OrderStatus resultStatus = OrderStatus.CONFIRMED;
        String failureReason = null;

        List<OrderItem> items = order.getItems();
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            try {
                reserveItem(order, item, i);
            } catch (InsufficientStockException | ProductNotFoundException ex) {
                resultStatus = OrderStatus.REJECTED;
                failureReason = ex.getMessage();
                break;
            } catch (InventoryServiceUnavailableException | InvalidInventoryResponseException ex) {
                resultStatus = OrderStatus.FAILED;
                failureReason = ex.getMessage();
                break;
            }
        }

        if (resultStatus != OrderStatus.CONFIRMED) {
            compensateReservedItems(order);
        }

        order.setStatus(resultStatus);
        order.setFailureReason(failureReason);
        // Recomputed here, from inventory's prices, before the save and therefore before the outbox
        // row is written - so the persisted total and the published event agree on a trusted number.
        order.setTotalAmount(resultStatus == OrderStatus.CONFIRMED ? trustedTotal(order) : BigDecimal.ZERO);
        Order saved = orderRepository.save(order);

        eventPublisher.publish(saved);
        log.info("Order {} finished with status={}", saved.getOrderNumber(), saved.getStatus());
        return OrderResponse.from(saved);
    }

    @Override
    public OrderResponse getOrder(String orderNumber) {
        return OrderResponse.from(findOrThrow(orderNumber));
    }

    @Override
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream().map(OrderResponse::from).toList();
    }

    /**
     * Reserves one line item and stamps it with the price inventory-service charged.
     * <p>
     * The price is taken from the reservation response, never from the request: inventory owns the
     * product and therefore owns its price. {@code reserved} is recorded before the price is checked,
     * because the two failures below are not the same thing and the difference decides whether
     * compensation has anything to release.
     */
    private void reserveItem(Order order, OrderItem item, int itemIndex) {
        // Keyed by line-item position, not SKU: a per-SKU key collides when one order repeats a
        // SKU, and inventory-service would then replay the first reservation instead of
        // decrementing again (oversell). The position is stable for the life of this call.
        String idempotencyKey = order.getOrderNumber() + "-" + itemIndex;
        StockReservationResponse response = inventoryClientAdapter.reserveStock(
                new StockReservationRequest(item.getSku(), item.getQuantity(), idempotencyKey));

        // Whatever inventory actually did, before anything else can throw.
        item.setReserved(response.reserved());

        if (!response.reserved()) {
            // Inventory declined - a business answer, so the order is REJECTED. Stock was not
            // decremented, so this line must not be "compensated" or it would create phantom units.
            // Reached when a previously-FAILED reservation is replayed: that answers 200 with
            // reserved=false rather than the 409 a first attempt would return.
            throw new InsufficientStockException(StringUtils.hasText(response.message())
                    ? response.message()
                    : "inventory-service did not reserve sku '" + item.getSku() + "'");
        }
        if (response.unitPrice() == null) {
            // Stock WAS decremented but the answer is unusable, so the line stays reserved=true and
            // compensation releases it. We cannot price the order and we will not guess.
            log.error("Invalid response from inventory-service for order={} sku={}: reserved with no unitPrice",
                    order.getOrderNumber(), item.getSku());
            throw new InvalidInventoryResponseException("inventory-service reserved sku '" + item.getSku()
                    + "' without a unitPrice");
        }
        item.setUnitPrice(response.unitPrice());
    }

    /**
     * The order total, summed from the prices inventory-service returned. Only ever called for a
     * CONFIRMED order, where every line is priced by construction - {@link #reserveItem} refuses to
     * leave a reserved line unpriced - but unpriced lines are skipped rather than trusted to be absent.
     */
    private BigDecimal trustedTotal(Order order) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : order.getItems()) {
            if (item.getUnitPrice() != null) {
                total = total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        }
        return total;
    }

    private void compensateReservedItems(Order order) {
        for (OrderItem item : order.getItems()) {
            if (item.isReserved()) {
                try {
                    inventoryClientAdapter.restock(item.getSku(), item.getQuantity());
                    item.setReserved(false);
                    log.info("Compensated (released) sku={} quantity={} for failed order={}",
                            item.getSku(), item.getQuantity(), order.getOrderNumber());
                } catch (Exception ex) {
                    // The release failed, so inventory is still decremented for this line.
                    // reserved stays true (the save after compensation persists it) so the order
                    // row records exactly what was not released, and the loop carries on so one
                    // bad compensation doesn't block the other line items.
                    log.error("COMPENSATION FAILED: order={} sku={} quantity={} is still reserved in inventory: {}",
                            order.getOrderNumber(), item.getSku(), item.getQuantity(), ex.toString());
                }
            }
        }
    }

    /**
     * Two lines with the same SKU used to produce the same reservation idempotency key, so
     * inventory-service replayed the first reservation instead of decrementing twice (oversell).
     * The order is rejected outright rather than silently merged, so the caller sees exactly
     * what it asked for. Comparison is exact: SKUs are opaque identifiers everywhere else.
     */
    private void validateNoDuplicateSkus(OrderRequest request) {
        Set<String> seen = new HashSet<>();
        for (OrderItemRequest itemRequest : request.items()) {
            if (!seen.add(itemRequest.sku())) {
                throw new DuplicateSkuException("sku '" + itemRequest.sku()
                        + "' appears more than once; combine the quantities into a single line item");
            }
        }
    }

    private Order buildPendingOrder(OrderRequest request) {
        Order order = Order.builder()
                .customerEmail(request.customerEmail())
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        // No prices yet, and deliberately none from the request: each line is priced by
        // inventory-service when it is reserved, and the total is summed from those prices once the
        // saga has finished. A PENDING order is not a payable order, so a zero total is honest here.
        for (OrderItemRequest itemRequest : request.items()) {
            OrderItem item = OrderItem.builder()
                    .sku(itemRequest.sku())
                    .quantity(itemRequest.quantity())
                    .reserved(false)
                    .build();
            order.addItem(item);
        }
        return order;
    }

    private Order findOrThrow(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException("Order '" + orderNumber + "' not found"));
    }
}
