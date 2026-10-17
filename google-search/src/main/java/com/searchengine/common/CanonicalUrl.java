package com.searchengine.common;

import java.util.Objects;
import java.util.Optional;

/**
 * Canonical_URL: the single normalized URL chosen to represent a set of duplicate or
 * equivalent URLs (design LLD §A "URL Normalization").
 *
 * <p>This type is the result of {@link UrlCanonicalizer#canonicalize(String)}. It is either:
 * <ul>
 *   <li>a <em>valid</em> Canonical_URL wrapping the normalized URL string, or</li>
 *   <li>the shared {@link #INVALID} sentinel, produced for any input that is not a
 *       syntactically valid absolute URL (Req 1.2).</li>
 * </ul>
 *
 * <p>Two URLs that differ only in scheme/host case, default port, fragment, or query
 * parameter order canonicalize to equal {@code CanonicalUrl} values, which is what makes
 * Frontier dedup (Req 1.6) and duplicate-content collapse (Req 4.6) deterministic.
 *
 * <p>Instances are immutable and implement value equality over the normalized string.
 */
public final class CanonicalUrl implements Comparable<CanonicalUrl> {

    /**
     * The sentinel returned for input that is not a syntactically valid absolute URL (Req 1.2).
     * There is exactly one INVALID instance; test for it with {@link #isValid()} or reference
     * equality against this constant.
     */
    public static final CanonicalUrl INVALID = new CanonicalUrl(null);

    private final String value;

    private CanonicalUrl(String value) {
        this.value = value;
    }

    /**
     * Wraps an already-normalized URL string as a valid Canonical_URL.
     *
     * @param normalized the normalized URL string; must be non-null
     * @return a valid {@code CanonicalUrl}
     * @throws NullPointerException if {@code normalized} is null
     */
    public static CanonicalUrl of(String normalized) {
        Objects.requireNonNull(normalized, "normalized URL must not be null");
        return new CanonicalUrl(normalized);
    }

    /** @return {@code true} if this is a valid Canonical_URL, {@code false} if it is {@link #INVALID}. */
    public boolean isValid() {
        return value != null;
    }

    /**
     * @return the normalized URL string.
     * @throws IllegalStateException if this is the {@link #INVALID} sentinel.
     */
    public String value() {
        if (value == null) {
            throw new IllegalStateException("INVALID CanonicalUrl has no value");
        }
        return value;
    }

    /** @return the normalized URL string if valid, otherwise empty. */
    public Optional<String> asOptional() {
        return Optional.ofNullable(value);
    }

    /**
     * Orders valid Canonical_URLs by their normalized string (ascending). The {@link #INVALID}
     * sentinel sorts before all valid values. This total order backs the deterministic
     * tie-break on ascending Canonical_URL (Req 1.4) and the "lexicographically smallest
     * Canonical_URL" duplicate rule (Req 4.6).
     */
    @Override
    public int compareTo(CanonicalUrl other) {
        if (this.value == null && other.value == null) {
            return 0;
        }
        if (this.value == null) {
            return -1;
        }
        if (other.value == null) {
            return 1;
        }
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanonicalUrl)) {
            return false;
        }
        return Objects.equals(value, ((CanonicalUrl) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value == null ? "CanonicalUrl(INVALID)" : "CanonicalUrl(" + value + ")";
    }
}
