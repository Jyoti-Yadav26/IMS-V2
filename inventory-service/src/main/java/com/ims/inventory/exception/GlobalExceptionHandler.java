package com.ims.inventory.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ProductNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, List.of(ex.getMessage()), req);
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateSkuException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, List.of(ex.getMessage()), req);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, List.of(ex.getMessage()), req);
    }

    @ExceptionHandler(ConcurrentStockUpdateException.class)
    public ResponseEntity<ErrorResponse> handleConcurrent(ConcurrentStockUpdateException ex, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, List.of(ex.getMessage()), req);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, List.of("The product was updated concurrently, please retry"), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, messages, req);
    }

    /**
     * Malformed/unparseable JSON is a bad request, not a server fault. Without this mapping it
     * fell through to {@link #handleGeneric} and surfaced as a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest req) {
        // Jackson's message quotes the offending input and its own class names, so it is logged
        // rather than returned: the client only needs to know the body could not be parsed.
        log.warn("Malformed request body on {} {}: {}", req.getMethod(), req.getRequestURI(),
                ex.getMostSpecificCause().toString());
        return build(HttpStatus.BAD_REQUEST, List.of("Malformed JSON request body"), req);
    }

    /**
     * Constraint violations on method parameters (e.g. {@code @Min} on a {@code @RequestParam} of a
     * {@code @Validated} controller) arrive as this exception rather than as
     * {@link MethodArgumentNotValidException}, which only covers {@code @Valid @RequestBody} beans.
     * Both are the caller's mistake, so both must be 400.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        List<String> messages = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .toList();
        return build(HttpStatus.BAD_REQUEST, messages, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        // The full type, message and stack trace go to the log where operators can see them. The
        // client gets a fixed message instead: ex.getMessage() routinely carries SQL fragments,
        // internal class names and host names, and leaking those tells an attacker how we are built.
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, List.of("An unexpected internal error occurred"), req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, List<String> messages, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), messages, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
