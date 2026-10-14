package com.ims.notification.listener;

import com.ims.notification.entity.NotificationType;
import com.ims.notification.event.OrderEvent;
import com.ims.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private OrderEventListener listener;

    @Test
    void onOrderEvent_confirmedMapsToConfirmedNotification() {
        OrderEvent event = new OrderEvent("evt-1", "ORD-1", "buyer@example.com", "CONFIRMED", null, Instant.now());

        listener.onOrderEvent(event);

        // The eventId is asserted rather than wildcarded: it is the de-duplication key, so a listener
        // that dropped it would leave the consumer non-idempotent while every other assertion passed.
        verify(notificationService).send(eq("evt-1"), eq(NotificationType.ORDER_CONFIRMED), eq("buyer@example.com"), any(), any(), eq("ORD-1"));
    }

    @Test
    void onOrderEvent_rejectedMapsToRejectedNotification() {
        OrderEvent event = new OrderEvent("evt-2", "ORD-2", "buyer@example.com", "REJECTED", "insufficient stock", Instant.now());

        listener.onOrderEvent(event);

        verify(notificationService).send(eq("evt-2"), eq(NotificationType.ORDER_REJECTED), eq("buyer@example.com"), any(), any(), eq("ORD-2"));
    }

    @Test
    void onOrderEvent_failedMapsToFailedNotification() {
        OrderEvent event = new OrderEvent("evt-3", "ORD-3", "buyer@example.com", "FAILED", "inventory-service down", Instant.now());

        listener.onOrderEvent(event);

        verify(notificationService).send(eq("evt-3"), eq(NotificationType.ORDER_FAILED), eq("buyer@example.com"), any(), any(), eq("ORD-3"));
    }
}
