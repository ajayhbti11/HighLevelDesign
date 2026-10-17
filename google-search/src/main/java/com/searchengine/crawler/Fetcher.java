package com.searchengine.crawler;

import com.searchengine.common.ContentRecord;
import com.searchengine.common.FailReason;
import com.searchengine.common.FetchOutcome;

import java.time.Clock;
import java.util.Objects;

/**
 * Fetcher: performs a single HTTP retrieval for a {@link FrontierEntry} and interprets the
 * transport response into a {@link FetchOutcome} (design §1.3, Req 3.1–3.7).
 *
 * <p>Behavior per attempt:
 * <ul>
 *   <li>Honors the effective robots delay before firing the request (Req 2.5/2.6 ordering,
 *       observed here as a pre-fetch delay) and applies the configured request timeout in
 *       {@code [1,120]}s (default 30) (Req 3.2).</li>
 *   <li><b>HTTP 200</b> → {@link FetchOutcome.Stored} carrying a complete {@link ContentRecord}:
 *       raw body, fetch timestamp, status, final resolved URL, and content type (Req 3.1).</li>
 *   <li><b>HTTP 5xx or timeout</b> → {@link FetchOutcome.Retry} with {@code retryCount + 1}
 *       (Req 3.2). The Crawler enforces the max-retries limit (Req 3.3).</li>
 *   <li><b>HTTP 3xx</b> → {@link FetchOutcome.Redirect} carrying the {@code Location} target
 *       (Req 3.4). The Crawler enqueues the target, records the edge, and enforces the redirect
 *       cap (Req 3.5). A 3xx with no usable {@code Location} is treated as a client error.</li>
 *   <li><b>HTTP 4xx</b> → {@link FetchOutcome.FailedPermanent} with
 *       {@link FailReason#CLIENT_ERROR_4XX}; no reschedule (Req 3.6).</li>
 * </ul>
 *
 * <p>The last {@link FetchAttempt} (Canonical_URL + timestamp + status) is exposed via
 * {@link #lastAttempt()} so the orchestrating {@link Crawler} can record it for every attempt
 * (Req 3.7). This class is not thread-safe.
 */
public final class Fetcher {

    /** Sleeps for the effective robots delay before a request fires (Req 2.5/2.6 ordering). */
    @FunctionalInterface
    public interface DelaySleeper {
        /** @param millis the number of milliseconds to wait; never negative */
        void sleep(long millis);
    }

    private final HttpTransport transport;
    private final FetcherConfig config;
    private final Clock clock;
    private final DelaySleeper sleeper;

    private FetchAttempt lastAttempt;

    /**
     * Creates a Fetcher with the system UTC clock and a no-op delay sleeper (useful for tests
     * that do not need to observe waiting).
     *
     * @param transport the injectable HTTP transport; must be non-null
     * @param config    the fetch configuration; must be non-null
     */
    public Fetcher(HttpTransport transport, FetcherConfig config) {
        this(transport, config, Clock.systemUTC(), millis -> { /* no-op */ });
    }

    /**
     * Creates a fully-injected Fetcher.
     *
     * @param transport the injectable HTTP transport; must be non-null
     * @param config    the fetch configuration; must be non-null
     * @param clock     the clock used for attempt timestamps; must be non-null
     * @param sleeper   the delay sleeper invoked before firing a request; must be non-null
     */
    public Fetcher(HttpTransport transport, FetcherConfig config, Clock clock, DelaySleeper sleeper) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /**
     * Fetches the given entry, honoring the effective robots delay, and interprets the response
     * into a {@link FetchOutcome} (Req 3.1–3.7).
     *
     * @param entry                 the Frontier entry to fetch; must be non-null
     * @param effectiveDelaySeconds the effective robots delay to honor before firing (from the
     *                              Robots Manager); must be non-negative
     * @return the {@link FetchOutcome} for this single attempt
     * @throws NullPointerException     if {@code entry} is null
     * @throws IllegalArgumentException if {@code effectiveDelaySeconds} is negative
     */
    public FetchOutcome fetch(FrontierEntry entry, double effectiveDelaySeconds) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (effectiveDelaySeconds < 0) {
            throw new IllegalArgumentException("effectiveDelaySeconds must be non-negative, got: " + effectiveDelaySeconds);
        }

        // Honor the robots delay before firing (Req 2.5/2.6 ordering).
        long delayMillis = (long) Math.ceil(effectiveDelaySeconds * 1000.0);
        if (delayMillis > 0) {
            sleeper.sleep(delayMillis);
        }

        String url = entry.canonicalUrl().value();
        HttpResponse response = transport.get(url, config.requestTimeoutSeconds());

        long timestamp = clock.millis();
        int status = response.statusCode(); // 0 denotes timeout (Req 3.2)
        this.lastAttempt = new FetchAttempt(url, timestamp, status); // Req 3.7

        // Timeout → transient failure to retry (Req 3.2).
        if (response.isTimedOut()) {
            return new FetchOutcome.Retry(entry.retryCount() + 1);
        }

        // HTTP 200 → store a complete ContentRecord (Req 3.1).
        if (status == 200) {
            ContentRecord record = new ContentRecord(
                    url,
                    response.finalUrl(),
                    response.body(),
                    status,
                    timestamp,
                    response.header("content-type").orElse(null));
            return new FetchOutcome.Stored(record);
        }

        // HTTP 3xx → redirect: enqueue target + record edge upstream (Req 3.4).
        if (status >= 300 && status < 400) {
            String location = response.header("location").filter(l -> !l.isBlank()).orElse(null);
            if (location != null) {
                return new FetchOutcome.Redirect(location);
            }
            // A redirect with no usable Location cannot be followed; treat as a client error.
            return new FetchOutcome.FailedPermanent(FailReason.CLIENT_ERROR_4XX);
        }

        // HTTP 4xx → permanent client error, no reschedule (Req 3.6).
        if (status >= 400 && status < 500) {
            return new FetchOutcome.FailedPermanent(FailReason.CLIENT_ERROR_4XX);
        }

        // HTTP 5xx → transient failure to retry (Req 3.2).
        if (status >= 500 && status < 600) {
            return new FetchOutcome.Retry(entry.retryCount() + 1);
        }

        // Any other status is treated as a transient failure so reachable content is not
        // permanently dropped on an unexpected code.
        return new FetchOutcome.Retry(entry.retryCount() + 1);
    }

    /**
     * @return the {@link FetchAttempt} recorded for the most recent {@link #fetch} call (timestamp
     *         + status), or {@code null} if no fetch has been performed yet (Req 3.7).
     */
    public FetchAttempt lastAttempt() {
        return lastAttempt;
    }
}
