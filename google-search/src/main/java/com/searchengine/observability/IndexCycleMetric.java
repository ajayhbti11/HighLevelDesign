package com.searchengine.observability;

/**
 * Counts recorded when an indexing cycle completes (Req 15.3).
 *
 * <p>Records how many Documents were added, updated, and removed during the cycle. Each count is
 * the number of index operations of that kind performed in the cycle (Correctness Property 45).
 *
 * @param added     count of Documents added in the cycle (non-negative)
 * @param updated   count of Documents updated in the cycle (non-negative)
 * @param removed   count of Documents removed in the cycle (non-negative)
 * @param timestamp epoch milliseconds at which the indexing cycle completed
 */
public record IndexCycleMetric(long added, long updated, long removed, long timestamp) {

    public IndexCycleMetric {
        if (added < 0) {
            throw new IllegalArgumentException("added must be non-negative, got: " + added);
        }
        if (updated < 0) {
            throw new IllegalArgumentException("updated must be non-negative, got: " + updated);
        }
        if (removed < 0) {
            throw new IllegalArgumentException("removed must be non-negative, got: " + removed);
        }
    }
}
