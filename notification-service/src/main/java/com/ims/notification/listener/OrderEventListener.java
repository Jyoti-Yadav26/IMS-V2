package com.ims.notification.listener;

import com.ims.notification.entity.NotificationType;
import com.ims.notification.event.OrderEvent;
import com.ims.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    private final NotificationService notificationService;

    @KafkaListener(topics = "order-events", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderEvent(OrderEvent event) {
        log.info("Received OrderEvent order={} status={}", event.orderNumber(), event.status());

        NotificationType type = switch (event.status()) {
            case "CONFIRMED" -> NotificationType.ORDER_CONFIRMED;
            case "REJECTED" -> NotificationType.ORDER_REJECTED;
            default -> NotificationType.ORDER_FAILED;
        };

        String subject = "Your order " + event.orderNumber() + " is " + event.status();
        String message = switch (type) {
            case ORDER_CONFIRMED -> "Good news! Your order has been confirmed and is being processed.";
            case ORDER_REJECTED -> "Sorry, your order could not be fulfilled: " + event.reason();
            default -> "We're having trouble processing your order right now. We'll retry automatically: " + event.reason();
        };

        notificationService.send(event.eventId(), type, event.customerEmail(), subject, message, event.orderNumber());
    }
}
