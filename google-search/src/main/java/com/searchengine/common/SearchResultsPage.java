package com.searchengine.common;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * SearchResultsPage: the ordered set of result entries returned for a Query (design §6, Req 11-13).
 *
 * @param entries           result entries in ranking order, at most the configured page size (Req 11.1, 11.3)
 * @param status            the page-level status (Req 7.4, 8.4, 11, 12, 13)
 * @param page              the 1-based page number this page represents
 * @param partialShardCount count of non-responding / uncovered shards, present only for
 *                          partial results (Req 12.4, 13.4)
 */
public record SearchResultsPage(
        List<ResultEntry> entries,
        PageStatus status,
        int page,
        OptionalInt partialShardCount) {

    public SearchResultsPage {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(partialShardCount, "partialShardCount must not be null");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** An ordinary page with entries and no partial-shard condition. */
    public static SearchResultsPage of(List<ResultEntry> entries, PageStatus status, int page) {
        return new SearchResultsPage(entries, status, page, OptionalInt.empty());
    }

    /** An empty page carrying a status (e.g. noQueryTerms, noMatches, invalidPage). */
    public static SearchResultsPage empty(PageStatus status, int page) {
        return new SearchResultsPage(List.of(), status, page, OptionalInt.empty());
    }

    /** A partial page recording the count of non-responding / uncovered shards (Req 12.4, 13.4). */
    public static SearchResultsPage partial(
            List<ResultEntry> entries, PageStatus status, int page, int partialShardCount) {
        return new SearchResultsPage(entries, status, page, OptionalInt.of(partialShardCount));
    }
}
