package com.searchengine.crawler;

/**
 * Injectable source of a host's {@link RobotsPolicy} (design §1.2). This is the seam that lets the
 * {@link RobotsManager} be exercised without any network I/O: production wiring supplies an
 * implementation that performs the actual {@code robots.txt} HTTP retrieval and parsing, while
 * tests supply a deterministic in-memory implementation.
 *
 * <p>Implementations must translate the underlying fetch into one of the three
 * {@link RobotsPolicy.Status} outcomes rather than throwing:
 * <ul>
 *   <li>a successful fetch and parse yields a {@link RobotsPolicy.Status#LOADED} policy;</li>
 *   <li>a not-found response (e.g. HTTP 404) yields a {@link RobotsPolicy.Status#MISSING} policy,
 *       so the host is treated as all-allowed (Req 2.3);</li>
 *   <li>a failed or malformed fetch yields a {@link RobotsPolicy.Status#MALFORMED} policy, so the
 *       host is treated as having no restrictions (Req 2.2).</li>
 * </ul>
 *
 * <p>To keep the manager robust, an implementation that instead throws a {@link RuntimeException}
 * is treated by {@link RobotsManager#ensurePolicy(String)} as a malformed fetch (Req 2.2). The
 * {@code fetchedAt} timestamp on the returned policy is used for the refresh-interval check
 * (Req 2.7); implementations should stamp it with the time the fetch was performed.
 */
@FunctionalInterface
public interface RobotsPolicyFetcher {

    /**
     * Fetches and parses the robots policy for {@code host}.
     *
     * @param host the host whose {@code robots.txt} should be retrieved (never {@code null})
     * @return the resulting {@link RobotsPolicy}; never {@code null}
     */
    RobotsPolicy fetch(String host);
}
