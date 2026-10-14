package com.ims.notification.service;

import com.ims.notification.entity.Notification;
import com.ims.notification.entity.NotificationType;
import com.ims.notification.repository.NotificationRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers both halves of the idempotent consumer, including the half the Kafka integration test
 * structurally cannot reach: sequential redelivery always finds the committed row in the pre-check,
 * so the constraint violation only happens when two consumers race. Fabricating the exception here is
 * the only way to prove it is absorbed rather than rethrown - and
 * {@code OrderEventIdempotentConsumerIntegrationTest} proves the real constraint produces exactly
 * this shape.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void send_withAnAlreadySeenEventId_skipsWithoutSaving() {
        when(notificationRepository.existsByEventId("evt-1")).thenReturn(true);

        Optional<Notification> result = send("evt-1");

        assertThat(result).isEmpty();
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void send_withANewEventId_persistsTheNotificationWithThatEventId() {
        when(notificationRepository.existsByEventId("evt-1")).thenReturn(false);
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Notification> result = send("evt-1");

        assertThat(result).isPresent();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        // The id has to reach the column, not just the log line: the column is the de-dup key.
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().getReferenceId()).isEqualTo("ORD-1");
    }

    @Test
    void send_whenAConcurrentDuplicateTripsTheUniqueConstraint_treatsItAsAlreadyDone() {
        when(notificationRepository.existsByEventId("evt-1")).thenReturn(false);
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(uniqueViolation("uk_notifications_event_id"));

        Optional<Notification> result = send("evt-1");

        // No exception, and no notification: the other consumer already sent it. Anything thrown here
        // would escape the @KafkaListener and be retried, then dead-lettered, by DefaultErrorHandler.
        assertThat(result).isEmpty();
    }

    @Test
    void send_whenAnUnrelatedIntegrityViolationOccurs_rethrows() {
        // A collision on something else is a real failure. Swallowing every integrity violation would
        // turn the de-dup into a silent drop for genuinely broken data.
        DataIntegrityViolationException unrelated = uniqueViolation("uk_notifications_something_else");
        when(notificationRepository.existsByEventId("evt-1")).thenReturn(false);
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenThrow(unrelated);

        assertThatThrownBy(() -> send("evt-1")).isSameAs(unrelated);
    }

    @Test
    void send_withABlankEventId_savesWithoutDeDuplicating() {
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        // Fails open: an event published before the id existed still reaches the customer.
        assertThat(send(null)).isPresent();
        assertThat(send("  ")).isPresent();

        verify(notificationRepository, never()).existsByEventId(any());
    }

    private Optional<Notification> send(String eventId) {
        return notificationService.send(eventId, NotificationType.ORDER_CONFIRMED, "buyer@example.com",
                "Your order ORD-1 is CONFIRMED", "Good news!", "ORD-1");
    }

    /** Shaped like a real Postgres unique-constraint failure as Spring Data surfaces it. */
    private static DataIntegrityViolationException uniqueViolation(String constraintName) {
        ConstraintViolationException pgUnique = new ConstraintViolationException(
                "duplicate key",
                new SQLException("duplicate key value violates unique constraint \"" + constraintName + "\"", "23505"),
                constraintName);
        return new DataIntegrityViolationException("duplicate key", pgUnique);
    }
}
