package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.DocId;

import java.util.Objects;

/**
 * Default {@link RefetchScheduler}: an adaptive multiplicative-backoff scheduler that shrinks a
 * Document's re-fetch interval when its normalized content changes and grows it when the content is
 * unchanged, clamped to configured bounds (design §1.4, Req 6.3).
 *
 * <p><b>Adaptation.</b> {@link #nextInterval(DocId, boolean, long)} multiplies the previous interval
 * by {@code decreaseFactor} (< 1) on a detected change and by {@code increaseFactor} (> 1) on no
 * change, then clamps the result to {@code [minIntervalMillis, maxIntervalMillis]}. Because the
 * result is clamped, an interval already at a bound will not move past it — a changed page pinned at
 * {@code minInterval} stays at {@code minInterval}, and an unchanged page pinned at
 * {@code maxInterval} stays at {@code maxInterval}. This satisfies both the direction requirement
 * (decrease on change, increase on no change) and the bounds requirement (Req 6.3).
 *
 * <p><b>Bounds.</b> The configured minimum must be at least {@link #ONE_MINUTE_MILLIS 1 minute} and
 * the configured maximum at most {@link #THIRTY_DAYS_MILLIS 30 days} (Req 6.3).
 *
 * <p><b>Scheduling.</b> {@link #schedule(DocId, CanonicalUrl, long)} delegates to a {@link RefetchSink}
 * (typically a {@link FrontierRefetchSink} that enqueues into the {@link Frontier}).
 *
 * <p>Instances are stateless beyond their configuration; the same instance may schedule any Document.
 */
public final class DefaultRefetchScheduler implements RefetchScheduler {

    /** One minute in milliseconds — the floor allowed for {@code minInterval} (Req 6.3). */
    public static final long ONE_MINUTE_MILLIS = 60L * 1000L;

    /** Thirty days in milliseconds — the ceiling allowed for {@code maxInterval} (Req 6.3). */
    public static final long THIRTY_DAYS_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    /** Default lower bound: 1 minute. */
    public static final long DEFAULT_MIN_INTERVAL_MILLIS = ONE_MINUTE_MILLIS;

    /** Default upper bound: 30 days. */
    public static final long DEFAULT_MAX_INTERVAL_MILLIS = THIRTY_DAYS_MILLIS;

    /** Default factor applied on a detected change (halve the interval). */
    public static final double DEFAULT_DECREASE_FACTOR = 0.5;

    /** Default factor applied on unchanged content (double the interval). */
    public static final double DEFAULT_INCREASE_FACTOR = 2.0;

    private final long minIntervalMillis;
    private final long maxIntervalMillis;
    private final double decreaseFactor;
    private final double increaseFactor;
    private final RefetchSink sink;

    /**
     * Creates a scheduler with the default bounds (1 minute .. 30 days) and default factors
     * (halve on change, double on no change), feeding the given sink.
     *
     * @param sink the destination for scheduled re-fetches; must be non-null
     */
    public DefaultRefetchScheduler(RefetchSink sink) {
        this(DEFAULT_MIN_INTERVAL_MILLIS, DEFAULT_MAX_INTERVAL_MILLIS,
                DEFAULT_DECREASE_FACTOR, DEFAULT_INCREASE_FACTOR, sink);
    }

    /**
     * Creates a fully configured scheduler.
     *
     * @param minIntervalMillis the minimum re-fetch interval, in ms; must be {@code >= 1 minute} (Req 6.3)
     * @param maxIntervalMillis the maximum re-fetch interval, in ms; must be {@code <= 30 days} and
     *                          {@code >= minIntervalMillis} (Req 6.3)
     * @param decreaseFactor    factor applied on a detected change; must be in {@code (0, 1)}
     * @param increaseFactor    factor applied on unchanged content; must be {@code > 1}
     * @param sink              the destination for scheduled re-fetches; must be non-null
     * @throws IllegalArgumentException if any bound or factor is out of range
     * @throws NullPointerException     if {@code sink} is null
     */
    public DefaultRefetchScheduler(long minIntervalMillis,
                                   long maxIntervalMillis,
                                   double decreaseFactor,
                                   double increaseFactor,
                                   RefetchSink sink) {
        if (minIntervalMillis < ONE_MINUTE_MILLIS) {
            throw new IllegalArgumentException(
                    "minInterval must be >= 1 minute (" + ONE_MINUTE_MILLIS + " ms), got: " + minIntervalMillis);
        }
        if (maxIntervalMillis > THIRTY_DAYS_MILLIS) {
            throw new IllegalArgumentException(
                    "maxInterval must be <= 30 days (" + THIRTY_DAYS_MILLIS + " ms), got: " + maxIntervalMillis);
        }
        if (maxIntervalMillis < minIntervalMillis) {
            throw new IllegalArgumentException(
                    "maxInterval (" + maxIntervalMillis + ") must be >= minInterval (" + minIntervalMillis + ")");
        }
        if (!(decreaseFactor > 0.0 && decreaseFactor < 1.0)) {
            throw new IllegalArgumentException("decreaseFactor must be in (0, 1), got: " + decreaseFactor);
        }
        if (!(increaseFactor > 1.0)) {
            throw new IllegalArgumentException("increaseFactor must be > 1, got: " + increaseFactor);
        }
        this.minIntervalMillis = minIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        this.decreaseFactor = decreaseFactor;
        this.increaseFactor = increaseFactor;
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies the decrease factor on {@code changed}, the increase factor otherwise, starting from
     * {@code prevInterval} first clamped into the configured bounds so that a caller-supplied value
     * outside the range (for example, an initial {@code 0}) still adapts correctly. The final result
     * is clamped to {@code [minInterval, maxInterval]} (Req 6.3).
     */
    @Override
    public long nextInterval(DocId docId, boolean changed, long prevInterval) {
        Objects.requireNonNull(docId, "docId must not be null");
        if (prevInterval < 0) {
            throw new IllegalArgumentException("prevInterval must be non-negative, got: " + prevInterval);
        }
        // Start from prevInterval clamped into range so factors act within the valid band.
        long base = clamp(prevInterval);
        double factor = changed ? decreaseFactor : increaseFactor;
        // Round to nearest ms; guard against overflow before clamping.
        double scaled = base * factor;
        long candidate;
        if (scaled >= (double) THIRTY_DAYS_MILLIS) {
            candidate = maxIntervalMillis;
        } else {
            candidate = Math.round(scaled);
        }
        return clamp(candidate);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the configured {@link RefetchSink}, which in production enqueues the re-fetch
     * into the {@link Frontier} (design §1.4).
     */
    @Override
    public void schedule(DocId docId, CanonicalUrl canonicalUrl, long at) {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        if (!canonicalUrl.isValid()) {
            throw new IllegalArgumentException("cannot schedule a re-fetch for an INVALID Canonical_URL");
        }
        sink.enqueue(docId, canonicalUrl, at);
    }

    /** @return the configured minimum re-fetch interval, in milliseconds. */
    public long minIntervalMillis() {
        return minIntervalMillis;
    }

    /** @return the configured maximum re-fetch interval, in milliseconds. */
    public long maxIntervalMillis() {
        return maxIntervalMillis;
    }

    /** Clamps a value into {@code [minIntervalMillis, maxIntervalMillis]}. */
    private long clamp(long value) {
        if (value < minIntervalMillis) {
            return minIntervalMillis;
        }
        if (value > maxIntervalMillis) {
            return maxIntervalMillis;
        }
        return value;
    }
}
