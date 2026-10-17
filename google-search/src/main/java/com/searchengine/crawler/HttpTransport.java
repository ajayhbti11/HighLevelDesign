package com.searchengine.crawler;

/**
 * Injectable HTTP transport abstraction used by the {@link Fetcher} (design §1.3).
 *
 * <p>Modeling the network behind this interface — which returns a {@link HttpResponse} carrying
 * status, body, headers, and final resolved URL — keeps the Fetcher's outcome-interpretation
 * logic (Req 3.1–3.7) fully unit-testable without any real network access. Production wires in a
 * real HTTP client; tests wire in a deterministic in-memory stub.
 */
@FunctionalInterface
public interface HttpTransport {

    /**
     * Performs a single HTTP GET for the given URL, applying the supplied request timeout.
     *
     * <p>Implementations MUST return {@link HttpResponse#timeout(String)} (rather than throwing)
     * when a complete response is not received within {@code timeoutSeconds}, so the Fetcher can
     * uniformly treat a timeout as a transient failure to retry (Req 3.2).
     *
     * @param url            the absolute URL to retrieve; must be non-null
     * @param timeoutSeconds the request timeout in seconds, already validated to be in [1,120]
     * @return the transport-level {@link HttpResponse}
     */
    HttpResponse get(String url, int timeoutSeconds);
}
