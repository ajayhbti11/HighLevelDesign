package com.searchengine.common;

import java.util.Objects;

/**
 * ResultEntry: a single item on the Search_Results_Page (design §6, Req 11.2, 11.7).
 *
 * <p>Every entry carries a title, Canonical_URL, and Snippet. When a title or snippet is
 * unavailable, the Serving_Layer substitutes the Canonical_URL for the missing title and an
 * empty string for the missing snippet (Req 11.7); the {@link #of} factory enforces this.
 *
 * @param title        the entry title (never null after substitution)
 * @param canonicalUrl the Canonical_URL of the result
 * @param snippet      the snippet excerpt (never null; empty string when unavailable)
 */
public record ResultEntry(String title, String canonicalUrl, String snippet) {

    public ResultEntry {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        Objects.requireNonNull(snippet, "snippet must not be null");
    }

    /**
     * Builds a ResultEntry applying the missing-field substitution rule (Req 11.7):
     * a null/blank title is replaced by the Canonical_URL, and a null snippet by "".
     */
    public static ResultEntry of(String title, String canonicalUrl, String snippet) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        String resolvedTitle = (title == null || title.isEmpty()) ? canonicalUrl : title;
        String resolvedSnippet = snippet == null ? "" : snippet;
        return new ResultEntry(resolvedTitle, canonicalUrl, resolvedSnippet);
    }
}
