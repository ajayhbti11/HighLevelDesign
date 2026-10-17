package com.searchengine.crawler;

/**
 * Configuration for the {@link Fetcher} and {@link Crawler}, with the bounds and defaults fixed by
 * Requirement 3 (design §1.3).
 *
 * <ul>
 *   <li>{@code requestTimeoutSeconds} — request timeout in seconds, in {@code [1,120]}, default
 *       {@code 30} (Req 3.2).</li>
 *   <li>{@code maxRetries} — maximum retry attempts for transient failures, in {@code [1,10]},
 *       default {@code 3} (Req 3.3).</li>
 *   <li>{@code maxRedirects} — maximum redirects in a single chain, in {@code [1,20]}, default
 *       {@code 5} (Req 3.5).</li>
 * </ul>
 *
 * <p>Instances are immutable and validated at construction.
 */
public final class FetcherConfig {

    /** Lower/upper bounds and defaults per Requirement 3. */
    public static final int MIN_TIMEOUT_SECONDS = 1;
    public static final int MAX_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public static final int MIN_MAX_RETRIES = 1;
    public static final int MAX_MAX_RETRIES = 10;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public static final int MIN_MAX_REDIRECTS = 1;
    public static final int MAX_MAX_REDIRECTS = 20;
    public static final int DEFAULT_MAX_REDIRECTS = 5;

    private final int requestTimeoutSeconds;
    private final int maxRetries;
    private final int maxRedirects;

    /**
     * Creates a validated configuration.
     *
     * @param requestTimeoutSeconds request timeout; must be in {@code [1,120]}
     * @param maxRetries            maximum retry attempts; must be in {@code [1,10]}
     * @param maxRedirects          maximum redirect chain length; must be in {@code [1,20]}
     * @throws IllegalArgumentException if any value is out of range
     */
    public FetcherConfig(int requestTimeoutSeconds, int maxRetries, int maxRedirects) {
        if (requestTimeoutSeconds < MIN_TIMEOUT_SECONDS || requestTimeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "requestTimeoutSeconds must be in [" + MIN_TIMEOUT_SECONDS + "," + MAX_TIMEOUT_SECONDS
                            + "], got: " + requestTimeoutSeconds);
        }
        if (maxRetries < MIN_MAX_RETRIES || maxRetries > MAX_MAX_RETRIES) {
            throw new IllegalArgumentException(
                    "maxRetries must be in [" + MIN_MAX_RETRIES + "," + MAX_MAX_RETRIES + "], got: " + maxRetries);
        }
        if (maxRedirects < MIN_MAX_REDIRECTS || maxRedirects > MAX_MAX_REDIRECTS) {
            throw new IllegalArgumentException(
                    "maxRedirects must be in [" + MIN_MAX_REDIRECTS + "," + MAX_MAX_REDIRECTS + "], got: " + maxRedirects);
        }
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.maxRetries = maxRetries;
        this.maxRedirects = maxRedirects;
    }

    /** @return a configuration using the Requirement 3 defaults (30s timeout, 3 retries, 5 redirects). */
    public static FetcherConfig defaults() {
        return new FetcherConfig(DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_RETRIES, DEFAULT_MAX_REDIRECTS);
    }

    /** @return the request timeout in seconds, in {@code [1,120]} (Req 3.2). */
    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    /** @return the maximum retry attempts, in {@code [1,10]} (Req 3.3). */
    public int maxRetries() {
        return maxRetries;
    }

    /** @return the maximum redirect chain length, in {@code [1,20]} (Req 3.5). */
    public int maxRedirects() {
        return maxRedirects;
    }
}
