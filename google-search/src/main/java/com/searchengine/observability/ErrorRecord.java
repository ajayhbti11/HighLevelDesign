package com.searchengine.observability;

import java.util.Objects;

/**
 * Structured error record written when a component operation fails (Req 15.4).
 *
 * <p>Carries the four fields the requirement mandates: the component name (for example
 * {@code "Fetcher"}, {@code "Indexer"}, {@code "ServingLayer"}), the operation name that failed,
 * the failure cause, and the epoch-millisecond timestamp of the failure. The timestamp also
 * supports retention and time-range querying by component (Req 15.5).
 *
 * @param component the name of the component whose operation failed; non-null
 * @param operation the name of the failing operation; non-null
 * @param cause     a human-readable description of the failure cause; non-null
 * @param timestamp epoch milliseconds at which the failure occurred
 */
public record ErrorRecord(String component, String operation, String cause, long timestamp) {

    public ErrorRecord {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
    }

    /**
     * Builds an error record from a {@link Throwable}, using its class name and message as the
     * recorded cause. Never dereferences a null message.
     *
     * @param component the name of the component whose operation failed
     * @param operation the name of the failing operation
     * @param cause     the exception describing the failure; non-null
     * @param timestamp epoch milliseconds at which the failure occurred
     */
    public static ErrorRecord fromThrowable(String component, String operation, Throwable cause, long timestamp) {
        Objects.requireNonNull(cause, "cause must not be null");
        String message = cause.getMessage();
        String description = message == null
                ? cause.getClass().getName()
                : cause.getClass().getName() + ": " + message;
        return new ErrorRecord(component, operation, description, timestamp);
    }
}
