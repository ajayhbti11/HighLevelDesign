package com.searchengine.serving;

import com.searchengine.common.PageStatus;
import com.searchengine.common.RankedResult;
import com.searchengine.common.ResultEntry;
import com.searchengine.common.SearchResultsPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pagination and Result_Entry assembly over a ranked result list (design §6, §H "Pagination";
 * Req 11).
 *
 * <p>Given the ranker's strict total order of {@link RankedResult}s (score descending, Doc_ID
 * ascending), {@link #paginate} returns the requested page as a contiguous slice of at most
 * {@code pageSize} entries, in ranker order. Because the input is a stable total order and each
 * page is the slice {@code [(page-1)*pageSize, page*pageSize)}, consecutive pages are disjoint and
 * no item is repeated across pages of the same query (Req 11.1, 11.3, 11.4).
 *
 * <p>Page-number and boundary handling (design §H):
 * <ul>
 *   <li>A page number less than 1 (or, in this typed API, otherwise invalid) yields an empty page
 *       with status {@link PageStatus#INVALID_PAGE} (Req 11.6). The non-integer case of the design
 *       pseudocode is represented here by the {@code int} parameter type: a caller parsing a
 *       non-integer page request should route it through {@link #paginate(List, String, int,
 *       MetadataResolver)}, which reports {@code INVALID_PAGE} for non-integer text.</li>
 *   <li>A start offset at or beyond the end of the ranked list yields an empty page with status
 *       {@link PageStatus#NO_FURTHER_RESULTS} (Req 11.5).</li>
 *   <li>Otherwise the contiguous slice is returned with status {@link PageStatus#OK}.</li>
 * </ul>
 *
 * <p>Each entry is assembled with a title, Canonical_URL, and Snippet (Req 11.2). A missing title
 * is substituted with the Canonical_URL and a missing snippet with an empty string (Req 11.7); see
 * {@link ResultEntry#of}.
 *
 * <p>The configured page size is bounded to {@code [1, 100]} with a default of {@code 10}
 * (Req 11.3). This class is stateless and thread-safe.
 */
public final class Paginator {

    /** Minimum configured page size (Req 11.3). */
    public static final int MIN_PAGE_SIZE = 1;

    /** Maximum configured page size (Req 11.3). */
    public static final int MAX_PAGE_SIZE = 100;

    /** Default page size when none is configured (Req 11.3). */
    public static final int DEFAULT_PAGE_SIZE = 10;

    private Paginator() {
        // static utility
    }

    /**
     * Returns the requested page of ranked results, assembling each into a {@link ResultEntry}
     * (Req 11).
     *
     * @param ranked   the full ranked result list in ranker order (score desc, Doc_ID asc); must
     *                 not be null but may be empty (Req 10.6)
     * @param page     the requested 1-based page number (Req 11.6: {@code < 1} is invalid)
     * @param pageSize the configured page size; must be within {@code [1, 100]} (Req 11.3)
     * @param resolver resolves display metadata for the Doc_IDs on the requested page
     * @return a {@link SearchResultsPage}: {@code INVALID_PAGE} for {@code page < 1};
     *         {@code NO_FURTHER_RESULTS} when the page starts beyond the last result; otherwise
     *         {@code OK} with at most {@code pageSize} entries in ranker order
     * @throws NullPointerException     if {@code ranked} or {@code resolver} is null
     * @throws IllegalArgumentException if {@code pageSize} is outside {@code [1, 100]}
     */
    public static SearchResultsPage paginate(
            List<RankedResult> ranked, int page, int pageSize, MetadataResolver resolver) {
        Objects.requireNonNull(ranked, "ranked must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        requireValidPageSize(pageSize);

        // Req 11.6: page number < 1 -> invalidPage, empty entries.
        if (page < 1) {
            return SearchResultsPage.empty(PageStatus.INVALID_PAGE, page);
        }

        // start = (page-1) * pageSize; use long to avoid overflow for large page numbers.
        long start = (long) (page - 1) * pageSize;

        // Req 11.5: start beyond the end -> noFurtherResults, empty entries.
        if (start >= ranked.size()) {
            return SearchResultsPage.empty(PageStatus.NO_FURTHER_RESULTS, page);
        }

        int from = (int) start;
        int to = (int) Math.min((long) from + pageSize, ranked.size());

        // Req 11.1, 11.3, 11.4: contiguous, disjoint slice of at most pageSize entries in order.
        List<ResultEntry> entries = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            RankedResult result = ranked.get(i);
            DocumentMetadata metadata = resolver.resolve(result.docId());
            Objects.requireNonNull(
                    metadata, () -> "resolver returned null metadata for " + result.docId());
            // Req 11.2 / 11.7: assemble title/Canonical_URL/snippet with substitution.
            entries.add(ResultEntry.of(metadata.title(), metadata.canonicalUrl(), metadata.snippet()));
        }

        return SearchResultsPage.of(entries, PageStatus.OK, page);
    }

    /**
     * Convenience overload accepting a raw, possibly non-integer page request (design §H, Req 11.6:
     * "less than 1 or is not an integer"). A page value that is not a base-10 integer, or that is
     * less than 1, yields an empty page with status {@link PageStatus#INVALID_PAGE}.
     *
     * @param pageText the raw requested page value (e.g. from a request parameter)
     */
    public static SearchResultsPage paginate(
            List<RankedResult> ranked, String pageText, int pageSize, MetadataResolver resolver) {
        Objects.requireNonNull(ranked, "ranked must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        requireValidPageSize(pageSize);

        int page;
        try {
            page = Integer.parseInt(pageText == null ? "" : pageText.trim());
        } catch (NumberFormatException notAnInteger) {
            // Req 11.6: non-integer page -> invalidPage. Page number is unknown; report 0.
            return SearchResultsPage.empty(PageStatus.INVALID_PAGE, 0);
        }
        return paginate(ranked, page, pageSize, resolver);
    }

    /**
     * Clamps a requested page size into the configured bounds {@code [1, 100]}, treating a
     * non-positive value as the default (Req 11.3). Useful for callers that accept an optional
     * page-size parameter.
     *
     * @param requested the requested page size ({@code <= 0} selects the default)
     * @return a page size within {@code [1, 100]}
     */
    public static int resolvePageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, requested));
    }

    private static void requireValidPageSize(int pageSize) {
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be within [" + MIN_PAGE_SIZE + ", " + MAX_PAGE_SIZE
                            + "], got: " + pageSize);
        }
    }
}
