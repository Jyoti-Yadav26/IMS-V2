package com.ims.notification.repository;

import com.ims.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * The idempotent consumer's pre-check: has this event already been turned into a notification?
     * Served by the index behind {@code uk_notifications_event_id}, which is also the backstop for the
     * race this check cannot win on its own.
     */
    boolean existsByEventId(String eventId);
}
