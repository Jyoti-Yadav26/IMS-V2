package com.ims.inventory.concurrency;

import com.ims.inventory.dto.StockReservationRequest;
import com.ims.inventory.dto.StockReservationResponse;
import com.ims.inventory.entity.ReservationStatus;
import com.ims.inventory.entity.StockReservation;
import com.ims.inventory.exception.ConcurrentStockUpdateException;
import com.ims.inventory.exception.InsufficientStockException;
import com.ims.inventory.service.ProductService;
import com.ims.inventory.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that justifies {@code @Version} on {@link com.ims.inventory.entity.Product}. Twenty
 * callers try to reserve the same single unit of stock at the same moment, each with its own
 * idempotency key so that nothing is de-duplicated and all twenty reach the decrement.
 *
 * <p>Exactly one must win. The failure this guards against is the classic lost update: every
 * transaction reads {@code quantity = 1}, every one concludes there is enough, and every one writes
 * {@code 0}. Note the shape of that bug - the stock never goes <em>negative</em>, because the
 * decrement is an absolute write computed from a stale read, not a relative one. It shows up as
 * twenty callers each told "reserved", nineteen units sold that do not exist, and a products row
 * that looks perfectly healthy at zero. That is why this test asserts on the number of successes
 * and the number of SUCCESS ledger rows, not just on the final quantity: the quantity alone cannot
 * tell the two outcomes apart.
 *
 * <p>Delete {@code @Version} from {@code Product} and this test fails. That is the check that it is
 * worth keeping.
 *
 * <p>How the losers fail is deliberately not asserted. {@code ProductServiceImpl} retries an
 * optimistic-lock conflict three times before giving up, so whether a given thread ends up seeing
 * "insufficient stock" (it got a fresh read showing zero) or "too much contention" (it burned its
 * retries) depends on how the twenty attempts interleave. Both are correct answers to "someone else
 * took it". The split is reported for insight rather than pinned down, because the system does not
 * promise a particular one.
 */
class StockReservationConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SKU = "SKU-LAST-UNIT";
    private static final int THREADS = 20;

    private enum Outcome {
        /** Told the caller the stock was theirs. */
        RESERVED,
        /** Correctly refused: by the time this thread read the row, the unit was gone. */
        INSUFFICIENT_STOCK,
        /** Correctly refused: optimistic-lock retries exhausted under contention. */
        CONTENTION,
        /** Anything else - would mean a failure mode this flow is not supposed to have. */
        UNEXPECTED
    }

    @Autowired
    private ProductService productService;

    @Test
    void twentySimultaneousReservationsOfOneUnit_sellItExactlyOnce() throws Exception {
        givenProduct(SKU, 1, 0);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch allThreadsReady = new CountDownLatch(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>(THREADS);
        List<Throwable> unexpected = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                // A distinct key per caller: this test is about the decrement racing itself, not
                // about idempotent replay. Shared keys are the other test's subject.
                String idempotencyKey = "last-unit-" + i;
                futures.add(pool.submit(() -> {
                    allThreadsReady.countDown();
                    startGate.await();
                    try {
                        StockReservationResponse response = productService.reserveStock(
                                new StockReservationRequest(SKU, 1, idempotencyKey));
                        return response.reserved() ? Outcome.RESERVED : Outcome.UNEXPECTED;
                    } catch (InsufficientStockException ex) {
                        return Outcome.INSUFFICIENT_STOCK;
                    } catch (ConcurrentStockUpdateException ex) {
                        return Outcome.CONTENTION;
                    } catch (RuntimeException ex) {
                        synchronized (unexpected) {
                            unexpected.add(ex);
                        }
                        return Outcome.UNEXPECTED;
                    }
                }));
            }

            // Hold every thread at the line until all twenty exist, then release them together.
            assertThat(allThreadsReady.await(30, TimeUnit.SECONDS))
                    .as("all %d threads should reach the start gate", THREADS)
                    .isTrue();
            startGate.countDown();

            Map<Outcome, Integer> tally = new EnumMap<>(Outcome.class);
            for (Future<Outcome> future : futures) {
                tally.merge(future.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            }

            System.out.printf(
                    "%n[concurrency] %d threads, 1 unit of stock -> reserved=%d, "
                            + "insufficientStock=%d, contention=%d, unexpected=%d%n",
                    THREADS,
                    tally.getOrDefault(Outcome.RESERVED, 0),
                    tally.getOrDefault(Outcome.INSUFFICIENT_STOCK, 0),
                    tally.getOrDefault(Outcome.CONTENTION, 0),
                    tally.getOrDefault(Outcome.UNEXPECTED, 0));

            assertThat(unexpected)
                    .as("no caller should fail in a way this flow does not model")
                    .isEmpty();

            // 1. Exactly one caller was told the unit was theirs.
            assertThat(tally.getOrDefault(Outcome.RESERVED, 0))
                    .as("exactly one of %d concurrent callers may reserve the only unit", THREADS)
                    .isEqualTo(1);

            // 2. Everyone else was refused, by one of the two legitimate routes.
            assertThat(tally.getOrDefault(Outcome.INSUFFICIENT_STOCK, 0)
                    + tally.getOrDefault(Outcome.CONTENTION, 0))
                    .as("every other caller must be refused")
                    .isEqualTo(THREADS - 1);

            // 3. The stock landed on zero, and never went below it.
            assertThat(quantityOf(SKU))
                    .as("one unit sold exactly once leaves zero, and stock must never go negative")
                    .isZero();

            // 4. The ledger agrees - this is what catches a lost update that still ends at zero.
            List<StockReservation> successes = reservationRepository.findAll().stream()
                    .filter(reservation -> reservation.getStatus() == ReservationStatus.SUCCESS)
                    .toList();
            assertThat(successes)
                    .as("the reservation ledger must record exactly one SUCCESS for one unit of stock")
                    .hasSize(1);
            assertThat(successes.get(0).getQuantity()).isEqualTo(1);
            assertThat(successes.get(0).getSku()).isEqualTo(SKU);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }
}
