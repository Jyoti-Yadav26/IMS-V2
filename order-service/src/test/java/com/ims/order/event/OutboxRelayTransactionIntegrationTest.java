package com.ims.order.event;

import com.ims.order.client.InventoryClientAdapter;
import com.ims.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Pins the one thing that putting {@code @Scheduled} and {@code @Transactional} on the same method
 * can get silently wrong. If the scheduler held the raw bean rather than its transactional proxy,
 * the relay would run with no transaction at all: {@code FOR UPDATE SKIP LOCKED} would claim
 * nothing, so two replicas would happily send the same rows, and each SENT update would autocommit
 * on its own. Nothing would fail - it would just quietly stop being an outbox.
 *
 * <p>inventory-service's {@code StockReservationExecutor} exists because of the same class of bug
 * approached from the other side (self-invocation through {@code this} bypassing the proxy). The
 * reason the relay does <em>not</em> need that split is asserted here rather than assumed: the
 * scheduler invokes the bean from outside, and the bean the container hands out is a proxy, so the
 * transaction advice applies.
 *
 * <p>The repository is a {@code @MockBean} so the Postgres-only claim query never reaches H2, and
 * the schedule is pushed an hour out so the background thread cannot race the stubbing - that is the
 * only reason {@code initialDelayString} exists on the annotation; it is a no-op in production.
 * Lives in {@code com.ims.order.event} rather than a concern package because {@code OutboxRelay} is
 * package-private.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "outbox.relay.enabled=true",
        "outbox.relay.initial-delay-ms=3600000",
        "outbox.relay.fixed-delay-ms=3600000"
})
class OutboxRelayTransactionIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @MockBean
    private OutboxEventRepository outboxEventRepository;

    /** Not exercised here; mocked only to keep the context free of a real inventory-service. */
    @MockBean
    private InventoryClientAdapter inventoryClientAdapter;

    @Test
    void relayPendingEvents_runsInsideARealTransaction() {
        AtomicBoolean transactionWasActive = new AtomicBoolean();
        when(outboxEventRepository.lockNewBatch(anyInt())).thenAnswer(invocation -> {
            transactionWasActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of();
        });

        relay.relayPendingEvents();

        assertThat(transactionWasActive).isTrue();
    }

    /**
     * The bean the container hands out is a proxy - and since Spring's scheduling post-processor
     * runs after the auto-proxy creator, that proxy is what it registered as the scheduled task's
     * target. Asserted this way rather than by reflecting into ScheduledTaskHolder internals, which
     * is brittle for no extra confidence.
     */
    @Test
    void theRelayBean_isAProxySoTheSchedulerCallGoesThroughTheTransactionAdvice() {
        assertThat(AopUtils.isAopProxy(relay)).isTrue();
        assertThat(AopUtils.getTargetClass(relay)).isEqualTo(OutboxRelay.class);
    }
}
