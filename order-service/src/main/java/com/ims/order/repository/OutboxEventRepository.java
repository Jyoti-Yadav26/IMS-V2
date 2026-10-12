package com.ims.order.repository;

import com.ims.order.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims the next batch of unsent events for this relay run. Must be called inside a transaction,
     * or the lock it takes is released immediately and buys nothing.
     *
     * <p>Native and deliberately Postgres-specific. {@code FOR UPDATE} holds the claimed rows for the
     * life of the transaction, and {@code SKIP LOCKED} makes a second order-service instance walk
     * past them instead of blocking on them - so several replicas drain one outbox in parallel and
     * never send the same row twice, where plain {@code FOR UPDATE} would just turn the relays into a
     * queue waiting on each other. {@code ORDER BY id} delivers in write order, and {@code SELECT *}
     * (rather than a column list) is what lets Hibernate hydrate the entity.
     *
     * <p>H2 cannot parse {@code SKIP LOCKED}, so this statement is never executed against the
     * in-memory test database. That is handled rather than ignored - the relay is switched off in
     * {@code src/test/resources/application.yml} so no H2-backed {@code @SpringBootTest} reaches it,
     * and {@code OutboxRelayTest} mocks this repository - but be honest about the cost: the SQL text
     * itself is only exercised by {@code OutboxRelayPostgresKafkaIntegrationTest} (real Postgres via
     * Testcontainers) and by the running service. Editing this string without Docker available means
     * shipping it unverified.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'NEW' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockNewBatch(@Param("limit") int limit);
}
