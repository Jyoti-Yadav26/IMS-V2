package com.ims.order.exception;

/** The same SKU appears on more than one line of one order - a client input error, rejected before any reservation. */
public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String message) {
        super(message);
    }
}
