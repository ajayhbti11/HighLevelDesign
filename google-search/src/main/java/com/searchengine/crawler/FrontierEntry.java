package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;

import java.util.Objects;

/**
 * A single scheduled URL in the {@link Frontier} (design §1.1 {@code FrontierEntry}).
 *
 * <p>Fields mirror the design contract:
 * <ul>
 *   <li>{@code canonicalUrl} — normalized absolute URL that serves as the dedup key (Req 1.6).</li>
 *   <li>{@code priority} — higher values are fetched sooner (Req 1.4).</li>
 *   <li>{@code retryCount} — incremented on transient failure and reschedule (Req 3.2).</li>
 *   <li>{@code host} — the URL host, used by politeness scheduling.</li>
 *   <li>{@code discoveredAt} — epoch milliseconds when the URL was discovered.</li>
 * </ul>
 *
 * <p>Instances are immutable; {@link #withRetryCount(int)} produces a copy with an updated
 * retry count for {@link Frontier#reschedule(FrontierEntry, int)}.
 */
public final class FrontierEntry {

    private final CanonicalUrl canonicalUrl;
    private final int priority;
    private final int retryCount;
    private final String host;
    private final long discoveredAt;

    /**
     * Creates a Frontier entry.
     *
     * @param canonicalUrl the valid Canonical_URL (dedup key); must be non-null and valid
     * @param priority     scheduling priority (higher = fetched sooner)
     * @param retryCount   number of retries so far; must be non-negative
     * @param host         the URL host; must be non-null
     * @param discoveredAt epoch milliseconds when discovered
     * @throws NullPointerException     if {@code canonicalUrl} or {@code host} is null
     * @throws IllegalArgumentException if {@code canonicalUrl} is invalid or {@code retryCount} is negative
     */
    public FrontierEntry(CanonicalUrl canonicalUrl, int priority, int retryCount, String host, long discoveredAt) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        Objects.requireNonNull(host, "host must not be null");
        if (!canonicalUrl.isValid()) {
            throw new IllegalArgumentException("FrontierEntry requires a valid Canonical_URL");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be non-negative");
        }
        this.canonicalUrl = canonicalUrl;
        this.priority = priority;
        this.retryCount = retryCount;
        this.host = host;
        this.discoveredAt = discoveredAt;
    }

    /** @return the valid Canonical_URL that uniquely identifies this entry (Req 1.6). */
    public CanonicalUrl canonicalUrl() {
        return canonicalUrl;
    }

    /** @return the scheduling priority; higher values are fetched sooner (Req 1.4). */
    public int priority() {
        return priority;
    }

    /** @return the number of retries recorded so far (Req 3.2). */
    public int retryCount() {
        return retryCount;
    }

    /** @return the URL host. */
    public String host() {
        return host;
    }

    /** @return epoch milliseconds when the URL was discovered. */
    public long discoveredAt() {
        return discoveredAt;
    }

    /**
     * Returns a copy of this entry with an updated retry count, preserving all other fields.
     *
     * @param newRetryCount the new retry count; must be non-negative
     * @return a new {@code FrontierEntry} with the given retry count
     * @throws IllegalArgumentException if {@code newRetryCount} is negative
     */
    public FrontierEntry withRetryCount(int newRetryCount) {
        return new FrontierEntry(canonicalUrl, priority, newRetryCount, host, discoveredAt);
    }

    /**
     * Value equality over all fields. Note: the {@link Frontier} deduplicates on
     * {@link #canonicalUrl()} alone (Req 1.6); this equality is for general collection use.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FrontierEntry)) {
            return false;
        }
        FrontierEntry that = (FrontierEntry) o;
        return priority == that.priority
                && retryCount == that.retryCount
                && discoveredAt == that.discoveredAt
                && canonicalUrl.equals(that.canonicalUrl)
                && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalUrl, priority, retryCount, host, discoveredAt);
    }

    @Override
    public String toString() {
        return "FrontierEntry{canonicalUrl=" + canonicalUrl
                + ", priority=" + priority
                + ", retryCount=" + retryCount
                + ", host=" + host
                + ", discoveredAt=" + discoveredAt
                + '}';
    }
}
