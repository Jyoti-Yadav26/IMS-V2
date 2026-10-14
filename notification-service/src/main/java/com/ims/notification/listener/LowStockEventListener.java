package com.ims.notification.listener;

import com.ims.notification.entity.NotificationType;
import com.ims.notification.event.LowStockEvent;
import com.ims.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LowStockEventListener {

    private static final String ADMIN_RECIPIENT = "inventory-admin@ims.local";

    private final NotificationService notificationService;

    @KafkaListener(topics = "low-stock-events", groupId = "${spring.kafka.consumer.group-id}")
    public void onLowStockEvent(LowStockEvent event) {
        log.info("Received LowStockEvent sku={} remaining={}", event.sku(), event.remainingQuantity());

        String subject = "Low stock alert: " + event.productName() + " (" + event.sku() + ")";
        String message = "Only " + event.remainingQuantity() + " unit(s) left, at or below the reorder threshold of "
                + event.reorderThreshold() + ". Please reorder soon.";

        notificationService.send(event.eventId(), NotificationType.LOW_STOCK, ADMIN_RECIPIENT, subject, message, event.sku());
    }
}
