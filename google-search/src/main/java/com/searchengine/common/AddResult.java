package com.searchengine.common;

/**
 * Result of attempting to add a URL to the Frontier (design §1.1).
 *
 * <ul>
 *   <li>{@link #ADDED} - the URL was syntactically valid and newly added (Req 1.1, 1.3).</li>
 *   <li>{@link #DUPLICATE_SKIPPED} - a URL with the same Canonical_URL is already present (Req 1.6).</li>
 *   <li>{@link #INVALID_REJECTED} - the URL was not a syntactically valid absolute URL (Req 1.2).</li>
 *   <li>{@link #ALREADY_FETCHED} - the URL is already present in the Content_Store (Req 1.3).</li>
 * </ul>
 */
public enum AddResult {
    ADDED,
    DUPLICATE_SKIPPED,
    INVALID_REJECTED,
    ALREADY_FETCHED
}
