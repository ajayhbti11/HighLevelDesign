package com.searchengine.observability;

/**
 * Counts recorded when a crawl cycle completes (Req 15.2).
 *
 * <p>Records the total number of fetch attempts and how many of those succeeded or failed for
 * the cycle. The counts are consistent by construction: {@code fetchAttempts == successes +
 * failures} (Correctness Property 45), which the canonical factory {@link #of(long, long)}
 * enforces by deriving the total from its parts.
 *
 * @param fetchAttempts total fetch attempts in the cycle (equals {@code successes + failures})
 * @param successes     successful fetch count (non-negative)
 * @param failures      failed fetch count (non-negative)
 * @param timestamp     epoch milliseconds at which the crawl cycle completed
 */
public record CrawlCycleMetric(long fetchAttempts, long successes, long failures, long timestamp) {

    public CrawlCycleMetric {
        if (successes < 0) {
            throw new IllegalArgumentException("successes must be non-negative, got: " + successes);
        }
        if (failures < 0) {
            throw new IllegalArgumentException("failures must be non-negative, got: " + failures);
        }
        if (fetchAttempts != successes + failures) {
            throw new IllegalArgumentException(
                    "fetchAttempts (" + fetchAttempts + ") must equal successes + failures ("
                            + (successes + failures) + ")");
        }
    }

    /**
     * Creates a crawl-cycle metric from its success and failure counts, deriving the total
     * fetch-attempt count as their sum so the consistency invariant always holds.
     *
     * @param successes successful fetch count (non-negative)
     * @param failures  failed fetch count (non-negative)
     * @param timestamp epoch milliseconds at which the crawl cycle completed
     */
    public static CrawlCycleMetric of(long successes, long failures, long timestamp) {
        return new CrawlCycleMetric(successes + failures, successes, failures, timestamp);
    }
}
