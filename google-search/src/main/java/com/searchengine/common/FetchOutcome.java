package com.searchengine.common;

import java.util.Objects;

/**
 * FetchOutcome: the result of a single Fetcher attempt (design §1.3).
 *
 * <p>Modeled as a sealed interface (a discriminated union) so the Crawler orchestrator can
 * exhaustively interpret each case:
 *
 * <ul>
 *   <li>{@link Stored} - HTTP 200; the complete ContentRecord was stored (Req 3.1).</li>
 *   <li>{@link Retry} - 5xx or timeout; reschedule with an incremented retry count (Req 3.2).</li>
 *   <li>{@link Redirect} - 3xx; enqueue the redirect target and record the edge (Req 3.4).</li>
 *   <li>{@link FailedPermanent} - permanent failure with a {@link FailReason} (Req 3.3/3.5/3.6).</li>
 * </ul>
 */
public sealed interface FetchOutcome
        permits FetchOutcome.Stored,
                FetchOutcome.Retry,
                FetchOutcome.Redirect,
                FetchOutcome.FailedPermanent {

    /** HTTP 200: the raw response and metadata were persisted to the Content_Store (Req 3.1). */
    record Stored(ContentRecord record) implements FetchOutcome {
        public Stored {
            Objects.requireNonNull(record, "record must not be null");
        }
    }

    /** 5xx / timeout: reschedule the URL with {@code retryCount} incremented by 1 (Req 3.2). */
    record Retry(int retryCount) implements FetchOutcome {
        public Retry {
            if (retryCount < 0) {
                throw new IllegalArgumentException("retryCount must be non-negative, got: " + retryCount);
            }
        }
    }

    /** 3xx: the redirect target to enqueue; the source to target edge is recorded (Req 3.4). */
    record Redirect(String target) implements FetchOutcome {
        public Redirect {
            Objects.requireNonNull(target, "target must not be null");
        }
    }

    /** Permanent failure: the URL is excluded from all further scheduling (Req 3.3/3.5/3.6). */
    record FailedPermanent(FailReason reason) implements FetchOutcome {
        public FailedPermanent {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
