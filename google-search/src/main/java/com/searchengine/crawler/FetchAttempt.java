package com.searchengine.crawler;

import java.util.Objects;

/**
 * A record of a single fetch attempt: the Canonical_URL attempted, the epoch-millisecond
 * timestamp, and the HTTP status code (Req 3.7).
 *
 * <p>Every attempt — success (200), transient failure (5xx/timeout), redirect (3xx), or permanent
 * failure (4xx) — produces exactly one {@code FetchAttempt}. A {@code httpStatus} of {@code 0}
 * denotes a request timeout, where no complete response was received (Req 3.2).
 *
 * @param canonicalUrl the Canonical_URL that was attempted
 * @param fetchTimestamp epoch milliseconds at which the attempt completed
 * @param httpStatus the HTTP status code, or {@code 0} for a timeout
 */
public record FetchAttempt(String canonicalUrl, long fetchTimestamp, int httpStatus) {

    public FetchAttempt {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
    }
}
