package com.ims.notification.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A record of every notification "sent" (in this demo we just log + persist rather than
 * calling a real email/SMS provider). Keeping history here - rather than only logging -
 * means notification-service has something meaningful to expose over its own REST API
 * and is independently useful/verifiable, e.g. from a support-agent tool.
 */
@Entity
@Table(name = "notifications",
        uniqueConstraints = @UniqueConstraint(name = "uk_notifications_event_id", columnNames = "event_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, length = 1000)
    private String message;

    /** orderNumber for order events, sku for low-stock events - kept generic and untyped on purpose. */
    @Column(name = "reference_id")
    private String referenceId;

    /**
     * Identity of the event that produced this notification, and the de-duplication key. Delivery is
     * at-least-once - order-service's outbox relay can crash after Kafka's ack but before the row is
     * marked SENT, and re-send the same event with the same id - so "one event, one notification" has
     * to be enforced here rather than assumed from the broker. {@code NotificationService} checks
     * {@code existsByEventId} first; this unique constraint is the backstop for the window between
     * that check and the insert, which opens as soon as there is more than one consumer thread or
     * replica. The constraint's implicit index is also what keeps that check cheap, so there is no
     * separate {@code @Index}.
     *
     * <p>Nullable on purpose, exactly like {@code orders.idempotency_key}: notifications written
     * before this column existed have no id, {@code ddl-auto: update} cannot add a NOT NULL column to
     * a populated table, and NULLs count as distinct under a unique constraint on both Postgres and
     * H2. An event arriving with no id therefore still notifies - failing open is the right trade for
     * a customer email.
     *
     * <p>Caveat worth knowing, in the same spirit as {@code OutboxEvent.payload}: because the schema
     * is managed by {@code ddl-auto: update}, Hibernate adds this column on an existing database and
     * emits the ALTER for the unique key, but its default
     * {@code UniqueConstraintSchemaUpdateStrategy} is DROP_RECREATE_QUIETLY - a failure is swallowed
     * and the application boots with the pre-check and no constraint behind it. On a long-lived volume
     * it should succeed (every existing row gets NULL), but to be certain:
     * <pre>
     * ALTER TABLE notifications ADD COLUMN IF NOT EXISTS event_id varchar(255);
     * ALTER TABLE notifications ADD CONSTRAINT uk_notifications_event_id UNIQUE (event_id);
     * </pre>
     */
    @Column(name = "event_id", updatable = false)
    private String eventId;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
