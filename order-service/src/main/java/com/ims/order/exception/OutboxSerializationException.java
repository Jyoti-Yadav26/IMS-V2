package com.ims.order.exception;

/**
 * An order event could not be written to the outbox because it could not be serialized.
 *
 * <p>Unchecked, and deliberately caught nowhere: it is thrown inside {@code createOrder}'s
 * transaction, so the order rolls back with it. That is the right answer - committing an order whose
 * event was silently dropped is precisely the dual write the outbox exists to remove, and the old
 * code's "log it and carry on" is what made that bug invisible in the first place.
 *
 * <p>No {@code GlobalExceptionHandler} mapping, which is a conscious departure from the convention
 * that every custom exception gets one. That convention exists so caller mistakes don't surface as
 * 500s; this is a programming error in our own serializer configuration, 500 is the correct status,
 * and the existing catch-all handler already logs the exception and returns a fixed message that
 * leaks no internals. An explicit handler would just be a copy of it.
 */
public class OutboxSerializationException extends RuntimeException {

    public OutboxSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
