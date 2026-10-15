package com.ims.inventory.idempotency;

import com.ims.inventory.dto.StockReservationRequest;
import com.ims.inventory.dto.StockReservationResponse;
import com.ims.inventory.entity.Product;
import com.ims.inventory.entity.ReservationStatus;
import com.ims.inventory.entity.StockReservation;
import com.ims.inventory.service.ProductService;
import com.ims.inventory.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reserving stock is not idempotent by nature - it decrements a counter - so order-service sends an
 * idempotency key and inventory-service keeps a ledger of what it has already done. This matters
 * because of a specific real failure: order-service times out waiting for a reply, retries, and the
 * first attempt had in fact succeeded. Without the ledger that retry decrements a second time and
 * the customer is charged once for stock taken twice.
 *
 * <p>Both halves of the key's behaviour are covered - the same key arriving one after the other, and
 * the same key arriving at the same instant. The second is the interesting one, because the two
 * paths through {@code ProductServiceImpl} are completely different: the sequential retry is caught
 * by a {@code findByIdempotencyKey} lookup before any work happens, while the simultaneous retry
 * gets past that check and is stopped by the unique constraint on
 * {@code stock_reservations.idempotency_key}.
 *
 * <p>This runs on real Postgres on purpose, and the concurrent case is the reason why. It is the
 * only test anywhere that drives
 * {@code ProductServiceImpl.replayAfterIdempotencyConflict} with a violation a database actually
 * raised; the unit test covering that path fabricates the exception, so it can only confirm the code
 * does what the test author assumed the database does.
 */
class StockReservationIdempotencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SKU = "SKU-IDEMPOTENT";
    private static final int STARTING_QUANTITY = 10;
    private static final int RESERVED_QUANTITY = 3;
    private static final int REMAINING_QUANTITY = STARTING_QUANTITY - RESERVED_QUANTITY;

    @Autowired
    private ProductService productService;

    @Test
    void theSameKeySentTwiceInSequence_decrementsStockOnce() {
        givenProduct(SKU, STARTING_QUANTITY, 0);
        StockReservationRequest request =
                new StockReservationRequest(SKU, RESERVED_QUANTITY, "sequential-retry");

        StockReservationResponse first = productService.reserveStock(request);
        StockReservationResponse replay = productService.reserveStock(request);

        // Both callers are told the same thing: the retry is indistinguishable from the original.
        assertThat(first.reserved()).isTrue();
        assertThat(replay.reserved()).isTrue();
        assertThat(first.remainingQuantity()).isEqualTo(REMAINING_QUANTITY);
        assertThat(replay.remainingQuantity()).isEqualTo(REMAINING_QUANTITY);
        // Including the price: order-service bills from this number, so a retry must not move it.
        assertThat(first.unitPrice()).isEqualByComparingTo("9.99");
        assertThat(replay.unitPrice()).isEqualByComparingTo("9.99");

        // But the stock moved exactly once, and the ledger holds one row rather than two.
        assertThat(quantityOf(SKU))
                .as("a replayed key must not decrement a second time")
                .isEqualTo(REMAINING_QUANTITY);
        assertThat(successfulReservations())
                .as("one reservation key, one ledger row")
                .hasSize(1);
    }

    /**
     * The price is part of the reserved outcome, so it has to be replayed like the rest of it.
     *
     * <p>This is the case that decides where the replayed price comes from. Reading
     * {@code Product.price} at replay time would be cheaper and needs no column - but then a price
     * change landing between a call and its retry makes the "idempotent" replay answer with a price
     * that was never reserved. The reservation ledger therefore stores the price as of the
     * reservation, and that is what this proves against real Postgres and a real column: the product
     * is repriced between the two calls and the replay is unmoved.
     *
     * <p>It matters more than it looks, because order-service builds the order total out of these
     * answers. Once a payment step exists, a retried line that silently reprices is the difference
     * between charging what was reserved and charging whatever the price happens to be now.
     */
    @Test
    void aReplayedKey_returnsThePriceAsOfTheReservationEvenAfterTheProductIsRepriced() {
        givenProduct(SKU, STARTING_QUANTITY, 0); // fixture price: 9.99
        StockReservationRequest request =
                new StockReservationRequest(SKU, RESERVED_QUANTITY, "repriced-retry");

        StockReservationResponse first = productService.reserveStock(request);
        assertThat(first.unitPrice()).isEqualByComparingTo("9.99");

        // An admin doubles the price between the original call and its retry.
        Product repriced = productRepository.findBySku(SKU).orElseThrow();
        repriced.setPrice(new BigDecimal("19.99"));
        productRepository.saveAndFlush(repriced);

        StockReservationResponse replay = productService.reserveStock(request);

        assertThat(replay.unitPrice())
                .as("a replay must answer with the price that was reserved, not the current one")
                .isEqualByComparingTo("9.99");
        assertThat(replay.reserved()).isTrue();
        assertThat(quantityOf(SKU)).isEqualTo(REMAINING_QUANTITY);
        assertThat(successfulReservations()).hasSize(1);
        // The frozen price really is on the ledger row, not inferred anywhere.
        assertThat(successfulReservations().get(0).getUnitPrice()).isEqualByComparingTo("9.99");
    }

    /**
     * The same key on two threads at once. Both get past the {@code findByIdempotencyKey} pre-check
     * because neither has committed yet, so the unique constraint is what has to stop the second
     * decrement. The loser then has to recognise that violation as a duplicate and reply with the
     * winner's result rather than propagating a 500.
     *
     * <p>That recognition is name-based and worth understanding, because it looks more fragile than
     * it is. {@code ProductServiceImpl.isIdempotencyKeyViolation} accepts a violation only when
     * SQLState is 23505 <em>and</em> the constraint's name contains "idempotency". No name is
     * declared anywhere in the entity: Hibernate folds
     * {@code @UniqueConstraint(columnNames = "idempotency_key")} and {@code @Column(unique = true)}
     * into a single inline column constraint, emitting
     * {@code idempotency_key varchar(255) not null unique}, which leaves the naming to the database.
     * Postgres names it {@code stock_reservations_idempotency_key_key} by its
     * {@code <table>_<column>_key} convention - so the substring matches, the violation is
     * classified correctly, and both callers get the same reserved response. Verified here, not
     * assumed.
     *
     * <p>The caveat is that this correctness is inherited from the dialect rather than stated by the
     * code. Any engine that names the constraint without reference to the column - H2 numbers them
     * {@code CONSTRAINT_*} - would push the loser down the rethrow path and turn a duplicate into a
     * 500. {@code orders} and {@code notifications} both name their constraints explicitly
     * ({@code uk_orders_idempotency_key}, {@code uk_notifications_event_id}) and do not depend on
     * this; {@code stock_reservations} is the one that does. Naming it is a one-line change, and a
     * separate one from this test.
     */
    @Test
    void theSameKeySentTwiceAtOnce_stillDecrementsStockOnce() throws Exception {
        givenProduct(SKU, STARTING_QUANTITY, 0);
        StockReservationRequest request =
                new StockReservationRequest(SKU, RESERVED_QUANTITY, "simultaneous-retry");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothThreadsReady = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>(2);

        try {
            Callable<Object> attempt = () -> {
                bothThreadsReady.countDown();
                startGate.await();
                try {
                    return productService.reserveStock(request);
                } catch (RuntimeException ex) {
                    return ex;
                }
            };
            futures.add(pool.submit(attempt));
            futures.add(pool.submit(attempt));

            assertThat(bothThreadsReady.await(30, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();

            List<Object> results = new ArrayList<>(2);
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            // Report anything that came back as an exception before asserting, so a regression here
            // shows what the caller actually saw rather than just a count.
            results.stream()
                    .filter(RuntimeException.class::isInstance)
                    .map(RuntimeException.class::cast)
                    .forEach(ex -> System.out.printf(
                            "%n[idempotency] concurrent duplicate key -> caller got %s: %s%n",
                            ex.getClass().getSimpleName(), ex.getMessage()));

            List<StockReservationResponse> responses = results.stream()
                    .filter(StockReservationResponse.class::isInstance)
                    .map(StockReservationResponse.class::cast)
                    .toList();
            System.out.printf(
                    "[idempotency] %d of 2 simultaneous callers received a reservation response%n",
                    responses.size());

            // 1. Neither caller sees an error: the loser is served the winner's result.
            assertThat(responses)
                    .as("a duplicate key must be replayed to the loser, not surfaced as a failure")
                    .hasSize(2);
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.reserved()).isTrue();
                assertThat(response.sku()).isEqualTo(SKU);
                assertThat(response.remainingQuantity()).isEqualTo(REMAINING_QUANTITY);
            });

            // 2. And the work happened once.
            assertThat(quantityOf(SKU))
                    .as("two simultaneous sends of one key must decrement stock once")
                    .isEqualTo(REMAINING_QUANTITY);
            assertThat(successfulReservations())
                    .as("one reservation key, one ledger row, even under a dead heat")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private List<StockReservation> successfulReservations() {
        return reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.SUCCESS)
                .toList();
    }
}
