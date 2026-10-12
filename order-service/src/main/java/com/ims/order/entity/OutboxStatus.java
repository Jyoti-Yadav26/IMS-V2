package com.ims.order.entity;

/**
 * Lifecycle of a row in the transactional outbox. Deliberately only two values: the relay's job is
 * to keep retrying until Kafka accepts an event, so there is no terminal "gave up" state - a row
 * that cannot be delivered announces itself through a climbing {@code attempts} count instead.
 */
public enum OutboxStatus {
    /** Written in the order's own transaction, not yet acknowledged by Kafka. The relay's work queue. */
    NEW,
    /** Kafka acknowledged the send. Kept afterwards as an audit trail of what was published. */
    SENT
}
