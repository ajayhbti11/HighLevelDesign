package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.DocId;

/**
 * Destination for scheduled re-fetches emitted by a {@link RefetchScheduler} (design §1.4:
 * {@code SCHED --> FRONTIER}).
 *
 * <p>Abstracting the destination behind this small interface keeps {@link DefaultRefetchScheduler}
 * unit-testable in isolation: production wiring uses {@link FrontierRefetchSink} to enqueue into the
 * {@link Frontier}, while tests can supply a recording sink.
 */
@FunctionalInterface
public interface RefetchSink {

    /**
     * Accepts a due re-fetch for the given Document.
     *
     * @param docId        the Document to re-fetch; non-null
     * @param canonicalUrl the Document's Canonical_URL to enqueue; a valid Canonical_URL
     * @param at           the epoch-millisecond time at which the re-fetch becomes due
     */
    void enqueue(DocId docId, CanonicalUrl canonicalUrl, long at);
}
