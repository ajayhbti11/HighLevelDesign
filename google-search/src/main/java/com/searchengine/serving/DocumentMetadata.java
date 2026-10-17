package com.searchengine.serving;

/**
 * The raw display metadata for a Document, used to assemble a {@code ResultEntry} (Req 11.2, 11.7).
 *
 * <p>Any of {@code title} or {@code snippet} may be {@code null} (or, for the title, empty) to
 * indicate the field is unavailable; the {@link Paginator} applies the substitution rule
 * (Req 11.7): a missing title becomes the Canonical_URL and a missing snippet becomes an empty
 * string. The {@code canonicalUrl} is always required.
 *
 * @param canonicalUrl the Canonical_URL of the Document (must not be null)
 * @param title        the Document title, or {@code null}/empty if unavailable
 * @param snippet      the snippet excerpt, or {@code null} if unavailable
 */
public record DocumentMetadata(String canonicalUrl, String title, String snippet) {
}
