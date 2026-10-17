package com.searchengine.crawler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of a single HTTP retrieval performed by an {@link HttpTransport}.
 *
 * <p>This is the transport-level value the {@link Fetcher} interprets into a
 * {@link com.searchengine.common.FetchOutcome}. Modeling the transport behind an interface that
 * returns this immutable record lets the Fetcher be unit-tested without any real network
 * (design §1.3).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code statusCode} — the HTTP status code (e.g. 200, 301, 404, 503). A value of {@code 0}
 *       is reserved for {@link #timeout(String)} to denote that no complete response arrived
 *       within the configured request timeout (Req 3.2).</li>
 *   <li>{@code body} — the raw response body bytes (empty for redirects/errors/timeouts).</li>
 *   <li>{@code headers} — response headers, looked up case-insensitively; used for
 *       {@code Location} (redirects, Req 3.4) and {@code Content-Type} (Req 3.1).</li>
 *   <li>{@code finalUrl} — the final resolved URL of the response (Req 3.1).</li>
 *   <li>{@code timedOut} — {@code true} when the transport did not receive a complete response
 *       within the request timeout (Req 3.2).</li>
 * </ul>
 */
public final class HttpResponse {

    private final int statusCode;
    private final byte[] body;
    private final Map<String, String> headers;
    private final String finalUrl;
    private final boolean timedOut;

    private HttpResponse(int statusCode, byte[] body, Map<String, String> headers, String finalUrl, boolean timedOut) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
        this.finalUrl = finalUrl;
        this.timedOut = timedOut;
    }

    /**
     * Builds a completed HTTP response.
     *
     * @param statusCode the HTTP status code; must be positive
     * @param body       the raw response body; must be non-null (use an empty array when absent)
     * @param headers    response headers; must be non-null (looked up case-insensitively)
     * @param finalUrl   the final resolved URL; must be non-null
     * @return an {@code HttpResponse} describing a completed retrieval
     */
    public static HttpResponse of(int statusCode, byte[] body, Map<String, String> headers, String finalUrl) {
        if (statusCode <= 0) {
            throw new IllegalArgumentException("statusCode must be positive for a completed response, got: " + statusCode);
        }
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(finalUrl, "finalUrl must not be null");
        Map<String, String> lower = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null) {
                lower.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return new HttpResponse(statusCode, body.clone(), Collections.unmodifiableMap(lower), finalUrl, false);
    }

    /**
     * Builds a timeout response: no complete response arrived within the request timeout (Req 3.2).
     * The status code is {@code 0} and the body is empty.
     *
     * @param requestedUrl the URL that timed out; must be non-null (used as the final URL)
     * @return an {@code HttpResponse} marked as timed out
     */
    public static HttpResponse timeout(String requestedUrl) {
        Objects.requireNonNull(requestedUrl, "requestedUrl must not be null");
        return new HttpResponse(0, new byte[0], Collections.emptyMap(), requestedUrl, true);
    }

    /** @return the HTTP status code; {@code 0} indicates a {@link #isTimedOut() timeout}. */
    public int statusCode() {
        return statusCode;
    }

    /** @return a defensive copy of the raw response body bytes. */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Case-insensitive header lookup.
     *
     * @param name the header name (case-insensitive); must be non-null
     * @return the header value if present
     */
    public Optional<String> header(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    /** @return the final resolved URL of the response (Req 3.1). */
    public String finalUrl() {
        return finalUrl;
    }

    /** @return {@code true} if the request timed out before a complete response (Req 3.2). */
    public boolean isTimedOut() {
        return timedOut;
    }
}
