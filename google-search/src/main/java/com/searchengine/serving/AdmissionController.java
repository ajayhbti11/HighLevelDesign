package com.searchengine.serving;

/**
 * Admission control / rate limiter for the Serving_Layer (design §6 "admission control + rate
 * limit"; Latency Budget "Admission + rate-limit check"; Req 12.3).
 *
 * <p>The serving plane accepts at most the configured <em>peak query rate</em> per window; any
 * query arriving once the window's budget is exhausted is <em>rejected</em> so the Serving_Layer
 * can return an {@code overloaded} page (Req 12.3). Accepted queries proceed through the pipeline
 * unaffected.
 *
 * <p>This models the design's "Reject excess, status = overloaded" edge case as a fixed-window
 * counter: within each window of {@code windowMillis} milliseconds at most {@code peakRate}
 * queries are admitted; the counter resets when the window rolls over. A fixed window is the
 * simplest structure that satisfies the invariant "accepted queries per window never exceed the
 * configured peak rate" (Property 39) while keeping admission an O(1), deterministic decision.
 *
 * <p>Instances are thread-safe: {@link #tryAdmit(long)} is synchronized so concurrent arrivals see
 * a consistent window count.
 */
public final class AdmissionController {

    private final int peakRate;
    private final long windowMillis;

    private long windowStart = Long.MIN_VALUE;
    private int admittedInWindow;

    private AdmissionController(int peakRate, long windowMillis) {
        if (peakRate < 0) {
            throw new IllegalArgumentException("peakRate must be non-negative, got: " + peakRate);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, got: " + windowMillis);
        }
        this.peakRate = peakRate;
        this.windowMillis = windowMillis;
    }

    /**
     * Creates an admission controller admitting at most {@code peakRate} queries per
     * {@code windowMillis} window (Req 12.3).
     *
     * @param peakRate     the maximum queries admitted per window (non-negative)
     * @param windowMillis the window length in milliseconds (positive)
     */
    public static AdmissionController of(int peakRate, long windowMillis) {
        return new AdmissionController(peakRate, windowMillis);
    }

    /**
     * Attempts to admit one query arriving at {@code nowMillis}.
     *
     * <p>Rolls the window over when {@code nowMillis} has advanced past the current window, then
     * admits the query iff the window's budget is not yet exhausted. The count of admitted queries
     * in any window never exceeds the configured peak rate (Req 12.3, Property 39).
     *
     * @param nowMillis the arrival time in epoch milliseconds
     * @return {@code true} iff the query is admitted; {@code false} to reject as overloaded
     */
    public synchronized boolean tryAdmit(long nowMillis) {
        if (windowStart == Long.MIN_VALUE || nowMillis - windowStart >= windowMillis) {
            // Start a fresh window aligned to this arrival.
            windowStart = nowMillis;
            admittedInWindow = 0;
        }
        if (admittedInWindow >= peakRate) {
            return false; // budget exhausted -> reject as overloaded (Req 12.3)
        }
        admittedInWindow++;
        return true;
    }

    /** The configured peak query rate per window (Req 12.3). */
    public int peakRate() {
        return peakRate;
    }

    /** The configured window length in milliseconds. */
    public long windowMillis() {
        return windowMillis;
    }
}
