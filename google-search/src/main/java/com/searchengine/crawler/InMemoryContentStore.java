package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.ContentRecord;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ContentStore} backing the {@link CrawlPipeline} when no durable store is
 * supplied (task 23.1: "use in-memory Content_Store if none exists").
 *
 * <p>Records are keyed by their Canonical_URL string. The map is a {@link ConcurrentHashMap} so the
 * store may be shared as the Frontier's {@link SeenSet} membership hook (Req 1.3) while the pipeline
 * writes fetched records (Req 3.1) and removes 404/410 documents (Req 6.2).
 */
public final class InMemoryContentStore implements ContentStore {

    private final ConcurrentHashMap<String, ContentRecord> byCanonicalUrl = new ConcurrentHashMap<>();

    @Override
    public void put(ContentRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        byCanonicalUrl.put(record.canonicalUrl(), record);
    }

    @Override
    public Optional<ContentRecord> get(String canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        return Optional.ofNullable(byCanonicalUrl.get(canonicalUrl));
    }

    @Override
    public boolean remove(String canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        return byCanonicalUrl.remove(canonicalUrl) != null;
    }

    @Override
    public boolean contains(CanonicalUrl canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        return canonicalUrl.isValid() && byCanonicalUrl.containsKey(canonicalUrl.value());
    }

    @Override
    public int size() {
        return byCanonicalUrl.size();
    }
}
