package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.ContentRecord;

import java.util.Optional;

/**
 * Content_Store: the durable store of raw fetched documents in the indexing plane (design
 * "Architecture data flow crawl -> parse -> index -> serve"; §1.3 Fetcher persists a complete
 * {@link ContentRecord} on HTTP 200, Req 3.1).
 *
 * <p>The store sits between the Fetcher and the Parser: the {@link CrawlPipeline} stores a record
 * here on a successful fetch and immediately reads it back for parsing (Req 4.1). It also backs the
 * Frontier's {@link SeenSet} membership hook so an already-fetched URL is not re-enqueued (Req 1.3).
 *
 * <p>Abstracting storage behind this interface keeps the pipeline testable with an
 * {@link InMemoryContentStore}; production wiring can supply a durable implementation.
 */
public interface ContentStore {

    /**
     * Stores (or replaces) the raw fetched record for its Canonical_URL (Req 3.1).
     *
     * @param record the fetched content record; must be non-null
     */
    void put(ContentRecord record);

    /**
     * Retrieves the stored record for a Canonical_URL, if any.
     *
     * @param canonicalUrl the Canonical_URL to look up; must be non-null
     * @return the stored {@link ContentRecord}, or empty when nothing is stored for that URL
     */
    Optional<ContentRecord> get(String canonicalUrl);

    /**
     * Removes any record stored for a Canonical_URL. Used when a re-fetch returns 404/410 so the
     * document is dropped from the store as well as the index (Req 6.2).
     *
     * @param canonicalUrl the Canonical_URL to remove; must be non-null
     * @return {@code true} if a record was present and removed
     */
    boolean remove(String canonicalUrl);

    /**
     * Reports whether the store already holds a record for a Canonical_URL. Wired into the
     * Frontier's {@link SeenSet} so a fetched URL is treated as seen and not re-enqueued (Req 1.3).
     *
     * @param canonicalUrl the Canonical_URL to test; must be non-null and valid
     * @return {@code true} if a record is stored for that URL
     */
    boolean contains(CanonicalUrl canonicalUrl);

    /** @return the number of records currently stored. */
    int size();
}
