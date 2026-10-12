package com.ims.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per domain event that must reach Kafka, written in the same database transaction as the
 * order it describes.
 *
 * <p>That atomicity is the whole point. The publish used to happen inside {@code createOrder} as a
 * fire-and-forget Kafka send whose failure was only logged, which is a dual write across two systems
 * with no shared commit: a broker outage left a confirmed order whose customer was never told, and a
 * rollback after a successful send left a notification for an order that does not exist. With the
 * event stored as a row, one commit decides both facts; {@link com.ims.order.event.OutboxRelay}
 * drains the rows afterwards.
 *
 * <p>What this does <em>not</em> remove is duplicates: the relay can crash after Kafka acknowledged
 * but before the row is marked SENT, so delivery is at-least-once. That is why every event carries a
 * stable {@link #eventId} for the consumer to de-duplicate on (CLAUDE.md issue 13).
 */
@Entity
// The relay polls "status = NEW ordered by id" once a second, forever, and SENT rows are retained,
// so without this index that poll degenerates into a scan of an ever-growing table. Same reasoning
// as the index on OrderItem.order_id.
@Table(name = "outbox_events",
        indexes = @Index(name = "idx_outbox_events_status_id", columnList = "status, id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_events_event_id", columnNames = "event_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identity of the event itself, generated once when this row is written and also embedded in
     * {@link #payload}. The consumer de-duplicates on it, so it has to be identical across every
     * redelivery - which is exactly why it is stored rather than generated at send time.
     */
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    /** The order this event is about, and the Kafka message key, so one order's events stay ordered. */
    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    /** Logical contract name; {@code OutboxRelay} maps it back to the event class. */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    /**
     * The serialized event. Deliberately a plain {@code varchar(4000)} rather than {@code @Lob} or
     * {@code columnDefinition = "text"}: these payloads are six short fields, and a LOB mapping is
     * the one thing that behaves differently on the two databases this code has to run on - H2 makes
     * it a CHARACTER LARGE OBJECT while Hibernate 6 reads Postgres CLOBs through a casting JDBC type -
     * so the H2 tests would stop proving anything about production. A plain varchar is the same
     * column and the same setString/getString binding on both.
     *
     * <p>Note the ceiling: because the schema is managed by {@code ddl-auto: update}, Hibernate
     * creates this column but will never widen it, so a future event that outgrows 4000 characters
     * needs a manual {@code ALTER TABLE}. 4000 is roughly fifteen times the current payload.
     */
    @Column(name = "payload", nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    /** Bumped on every failed send. A row with a climbing count is the operator's alarm. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = OutboxStatus.NEW;
        }
        createdAt = Instant.now();
    }
}
