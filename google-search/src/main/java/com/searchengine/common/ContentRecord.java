package com.searchengine.common;

import java.util.Objects;

/**
 * ContentRecord: a raw fetched document stored in the Content_Store (design Data Models).
 *
 * <p>On an HTTP 200 the Fetcher stores the raw response body, fetch timestamp, HTTP status
 * code, final resolved URL, and content type (Req 3.1). Every fetch attempt records at least
 * the timestamp and HTTP status (Req 3.7).
 *
 * @param canonicalUrl   the Canonical_URL the fetch was scheduled for
 * @param finalUrl       the final resolved URL after any redirects
 * @param rawBody        the raw response body bytes (may be empty for non-200 attempts)
 * @param httpStatus     the HTTP status code of the response
 * @param fetchTimestamp epoch milliseconds at which the fetch attempt completed
 * @param contentType    the response content type (may be null when not provided)
 */
public record ContentRecord(
        String canonicalUrl,
        String finalUrl,
        byte[] rawBody,
        int httpStatus,
        long fetchTimestamp,
        String contentType) {

    public ContentRecord {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        Objects.requireNonNull(finalUrl, "finalUrl must not be null");
        Objects.requireNonNull(rawBody, "rawBody must not be null");
    }
}
