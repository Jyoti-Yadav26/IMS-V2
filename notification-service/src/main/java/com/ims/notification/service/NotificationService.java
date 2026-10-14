package com.ims.notification.service;

import com.ims.notification.entity.Notification;
import com.ims.notification.entity.NotificationType;
import com.ims.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Turns an event into at most one notification.
 *
 * <p>Kafka delivery here is at-least-once, not exactly-once: order-service's outbox relay can crash
 * after the broker acknowledged a send but before the outbox row is marked SENT, and its send timeout
 * can fire on a record that later succeeds - both re-send the same event, carrying the same
 * {@code eventId}. So a duplicate has to be a no-op at this end; the consumer, not the broker, owns
 * "the customer is emailed once".
 *
 * <p>Two mechanisms, because neither is sufficient alone. The {@code existsByEventId} pre-check
 * handles the ordinary case cheaply - a redelivery minutes later, already committed. But the check and
 * the insert run in two different transactions (this class is not {@code @Transactional}, so each
 * repository call opens and commits its own), which leaves a real time-of-check/time-of-use window.
 * Inside one instance that window is unreachable: the same {@code eventId} rides the same message key,
 * so the same partition, so the same thread, in order. It opens as soon as there are several consumer
 * threads or several replicas, or across a rebalance - and that is what the unique constraint on
 * {@code notifications.event_id} closes. A violation there is not a failure, it is proof that someone
 * else already did the work, so it is logged and swallowed.
 *
 * <p>Swallowing it matters more than it looks. {@code DataIntegrityViolationException} is not in
 * {@code DefaultErrorHandler}'s non-retryable set, so letting it escape the listener would stall the
 * partition for three one-second retries and log a stack trace per duplicate - and then "succeed" on
 * attempt two, because by then the pre-check finds the committed row. Nothing would be lost, but
 * relying on the retry machinery to launder a known-benign collision is not a design.
 *
 * <p>Note what this class deliberately does <em>not</em> do: it does not copy
 * {@code order-service}'s {@code IdempotentOrderCreator} and push the catch into a separate bean.
 * That bean exists because its violation surfaces inside an enclosing {@code @Transactional} saga that
 * JPA marks rollback-only, making both the catch and any read after it unsafe in place. There is no
 * enclosing transaction here, so the same shape without that reason would be ceremony.
 *
 * <p>An event with no {@code eventId} fails open: it is persisted without de-duplication rather than
 * dropped. That covers events published before the id existed, and a missing id should never be the
 * reason a customer hears nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * Stands in for an actual email/SMS/push provider call - logs and persists instead.
     *
     * @return the stored notification, or empty when this {@code eventId} had already been processed
     *         and nothing was sent.
     */
    public Optional<Notification> send(String eventId, NotificationType type, String recipient,
                                       String subject, String message, String referenceId) {
        boolean deDuplicable = StringUtils.hasText(eventId);
        if (deDuplicable && notificationRepository.existsByEventId(eventId)) {
            log.info("Skipping duplicate event eventId={} reference={} - already notified", eventId, referenceId);
            return Optional.empty();
        }

        Notification notification = Notification.builder()
                .type(type)
                .recipient(recipient)
                .subject(subject)
                .message(message)
                .referenceId(referenceId)
                .eventId(eventId)
                .build();
        try {
            // saveAndFlush, not save: the violation has to surface from this call so the catch below
            // can see it. IDENTITY generation happens to force the INSERT at persist time today, but
            // that would quietly stop being true if the id strategy changed or if anyone added
            // @Transactional here - then save would defer the INSERT to an outer commit and this
            // catch would silently stop catching.
            Notification saved = notificationRepository.saveAndFlush(notification);
            log.info("Notification sent [{}] to={} subject='{}' eventId={}", type, recipient, subject, eventId);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException ex) {
            if (!deDuplicable || !isEventIdViolation(ex)) {
                throw ex;
            }
            log.info("Concurrent duplicate eventId={} reference={} - another consumer already notified",
                    eventId, referenceId);
            return Optional.empty();
        }
    }

    public List<Notification> getAll() {
        return notificationRepository.findAll();
    }

    /**
     * Only a collision on the event id is a duplicate; every other integrity violation (a null column,
     * an over-long message) is a real failure and must keep propagating to the error handler. Copied
     * from {@code inventory-service}'s {@code ProductServiceImpl} and {@code order-service}'s
     * {@code IdempotentOrderCreator}: match SQLState 23505 plus the constraint name, and fall back to
     * the message because the driver's {@code SQLException} is usually the most specific cause. The
     * constraint is named {@code uk_notifications_event_id} so the name check works on Postgres and
     * H2 alike.
     */
    private boolean isEventIdViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof ConstraintViolationException constraintEx) {
            if (!"23505".equals(constraintEx.getSQLState())) {
                return false;
            }
            String constraint = constraintEx.getConstraintName();
            return constraint != null && constraint.toLowerCase().contains("event_id");
        }
        String message = cause != null ? cause.getMessage() : ex.getMessage();
        return message != null && message.toLowerCase().contains("event_id");
    }
}
