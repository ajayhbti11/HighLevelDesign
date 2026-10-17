package com.searchengine.crawler;

import java.util.Objects;

/**
 * A recorded source-to-target redirect relationship (Req 3.4).
 *
 * <p>When the Fetcher receives an HTTP 3xx response, the Crawler enqueues the redirect target and
 * records the {@code source -> target} edge. These edges form the link graph the Ranker later uses
 * for the document-authority component (design §G).
 *
 * @param source the Canonical_URL (or raw source URL) that returned the redirect
 * @param target the redirect target URL that was enqueued
 */
public record RedirectEdge(String source, String target) {

    public RedirectEdge {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
    }
}
