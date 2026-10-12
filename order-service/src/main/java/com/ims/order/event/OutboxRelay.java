package com.ims.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.order.entity.OutboxEvent;
import com.ims.order.entity.OutboxStatus;
import com.ims.order.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Drains the outbox to Kafka. This is the half of the pattern that runs <em>after</em> the order
 * transaction committed, which is what stops a broker outage from affecting whether an order can be
 * placed at all - it only delays the notification.
 *
 * <p>Each run is one transaction: claim up to {@value #BATCH_SIZE} NEW rows with
 * {@code FOR UPDATE SKIP LOCKED}, send each one, mark it SENT. The lock is what makes several
 * order-service replicas safe - each claims a disjoint set of rows - and the transaction is what
 * makes the lock last long enough to matter.
 *
 * <p>Two deliberate asymmetries in the failure handling:
 * <ul>
 *   <li>A <b>send</b> failure stops the batch. The broker is presumably down, so the remaining rows
 *       would fail the same way; stopping preserves per-order write order and avoids spending five
 *       seconds a row on a dead broker.</li>
 *   <li>A row we can <b>never</b> send - unknown {@code eventType}, unparseable payload - is logged,
 *       counted and skipped. Stopping the batch on it would wedge the relay permanently: every other
 *       order's event would queue behind one undeliverable row, forever, one retry per second.</li>
 * </ul>
 * Either way the method returns normally so the transaction commits. The {@code attempts} increments
 * are the only record of the trouble, and rethrowing would roll back the very bookkeeping just
 * written.
 *
 * <p>Delivery is at-least-once, not exactly-once: a crash between Kafka's ack and the SENT update
 * redelivers the event, and the five-second wait can time out on a record that is still in flight
 * and later succeeds. Hence the stable eventId on the event, and the separate requirement that the
 * consumer be idempotent.
 *
 * <p>Accepted cost of the specified design: a five-second wait per row while holding row locks, so a
 * flaky broker can hold them for up to {@value #BATCH_SIZE} times that. {@code SKIP LOCKED} keeps
 * other replicas from blocking on it, and a fixed delay on Boot's single scheduler thread means two
 * runs can never overlap within one instance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    /**
     * Maps the stored {@code eventType} back to its contract class, which is the point of this whole
     * indirection: the row holds JSON, but Kafka has to be handed the typed object, because the
     * producer's configured {@code JsonSerializer.TYPE_MAPPINGS} is what stamps the agreed
     * "order-event" token into the type header. Sending the stored String (or a JsonNode) would
     * stamp {@code java.lang.String} instead, which notification-service does not trust, and every
     * event would land in the dead-letter topic - re-breaking CLAUDE.md issue 4.
     *
     * <p>Deliberately a map and not {@code Class.forName(row.getEventType())}: that would turn a
     * database column into classloader input and couple stored rows to our package names, which is
     * the exact coupling the type token exists to avoid.
     */
    private static final Map<String, Class<?>> EVENT_TYPES =
            Map.of(OrderEventPublisher.ORDER_EVENT_TYPE, OrderEvent.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}",
            initialDelayString = "${outbox.relay.initial-delay-ms:0}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.lockNewBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return; // the common case, once a second - say nothing
        }
        for (OutboxEvent row : batch) {
            Object event = toEvent(row);
            if (event == null) {
                countAttempt(row); // already logged; skip it rather than block the batch
                continue;
            }
            try {
                kafkaTemplate.send(row.getTopic(), row.getAggregateId(), event)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                recordSendFailure(row, ex);
                break;
            } catch (Exception ex) {
                recordSendFailure(row, ex);
                break;
            }
            row.setStatus(OutboxStatus.SENT);
            row.setSentAt(Instant.now());
            outboxEventRepository.save(row);
            log.info("Relayed outbox eventId={} topic={} key={}",
                    row.getEventId(), row.getTopic(), row.getAggregateId());
        }
    }

    /** Returns the typed event, or null when this row can never be sent (already logged). */
    private Object toEvent(OutboxEvent row) {
        Class<?> type = EVENT_TYPES.get(row.getEventType());
        if (type == null) {
            log.error("Outbox row id={} eventId={} has unknown eventType '{}'; skipping it. This is a "
                            + "deployment problem, not a broker problem - the row stays NEW.",
                    row.getId(), row.getEventId(), row.getEventType());
            return null;
        }
        try {
            return objectMapper.readValue(row.getPayload(), type);
        } catch (JsonProcessingException ex) {
            log.error("Outbox row id={} eventId={} has a payload that cannot be read as {}; skipping it",
                    row.getId(), row.getEventId(), type.getSimpleName(), ex);
            return null;
        }
    }

    private void recordSendFailure(OutboxEvent row, Exception ex) {
        countAttempt(row);
        log.error("Failed to relay outbox eventId={} key={} (attempt {}); leaving it NEW and stopping "
                        + "this batch: {}",
                row.getEventId(), row.getAggregateId(), row.getAttempts(), ex.toString());
    }

    private void countAttempt(OutboxEvent row) {
        row.setAttempts(row.getAttempts() + 1);
        outboxEventRepository.save(row);
    }
}
