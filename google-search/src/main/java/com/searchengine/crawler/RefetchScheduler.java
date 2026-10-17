package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.DocId;

/**
 * Re-fetch Scheduler: decides when each indexed Document should be crawled again and enqueues the
 * re-fetch (design §1.4 {@code RefetchScheduler}, Req 6.3).
 *
 * <p>The scheduler is <em>adaptive</em>: the interval between re-fetches shrinks while a Document's
 * normalized content keeps changing (so volatile pages are revisited sooner) and grows while the
 * content stays the same (so stable pages are revisited less often). The interval is always kept
 * within the configured bounds {@code [minInterval, maxInterval]}, where
 * {@code minInterval >= 1 minute} and {@code maxInterval <= 30 days} (Req 6.3).
 *
 * @see DefaultRefetchScheduler
 */
public interface RefetchScheduler {

    /**
     * Computes the next re-fetch interval for a Document from the outcome of the latest re-fetch.
     *
     * <p>The result decreases relative to {@code prevInterval} when {@code changed} is {@code true}
     * (content changed) and increases when {@code changed} is {@code false} (content unchanged),
     * clamped to the configured {@code [minInterval, maxInterval]} bounds (Req 6.3).
     *
     * @param docId        the Document whose interval is being adapted; must be non-null
     * @param changed      {@code true} if the latest re-fetch detected changed normalized content
     * @param prevInterval the previous re-fetch interval, in milliseconds; must be non-negative
     * @return the next re-fetch interval, in milliseconds, within the configured bounds
     */
    long nextInterval(DocId docId, boolean changed, long prevInterval);

    /**
     * Schedules a re-fetch of the given Document at (or after) the supplied time by enqueuing it for
     * crawling (design §1.4). Implementations typically feed the Frontier (§1.1).
     *
     * @param docId        the Document to re-fetch; must be non-null
     * @param canonicalUrl the Document's Canonical_URL to enqueue; must be a valid Canonical_URL
     * @param at           the epoch-millisecond time at which the re-fetch becomes due
     */
    void schedule(DocId docId, CanonicalUrl canonicalUrl, long at);
}
