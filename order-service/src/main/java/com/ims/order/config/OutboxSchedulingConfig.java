package com.ims.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns Spring's scheduler on for the one background job this service has, {@code OutboxRelay}.
 *
 * <p>Deliberately a new class rather than another annotation on {@code OrderServiceApplication}: it
 * leaves the application class untouched, and it puts the scheduler behind the <em>same</em>
 * {@code outbox.relay.enabled} flag as the relay bean, so switching that flag off (as the test
 * profile does) removes the relay and its scheduler thread together, instead of leaving a scheduler
 * running with nothing to schedule.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulingConfig {
}
