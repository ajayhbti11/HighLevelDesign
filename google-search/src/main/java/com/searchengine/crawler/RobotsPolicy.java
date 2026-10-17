package com.searchengine.crawler;

import java.util.List;
import java.util.Objects;

/**
 * Robots_Policy: the set of crawling rules published by a host via its {@code robots.txt}
 * (design §1.2 {@code RobotsPolicy}, Requirement 2).
 *
 * <p>Fields mirror the design contract:
 * <ul>
 *   <li>{@code host} — the host these rules govern.</li>
 *   <li>{@code disallowRules} — path prefixes that are disallowed (Req 2.4).</li>
 *   <li>{@code allowRules} — explicit allow path prefixes; the longest-matching rule wins over a
 *       disallow rule (Req 2.4).</li>
 *   <li>{@code crawlDelaySeconds} — the host-specified crawl-delay, or {@code null} when the host
 *       does not specify one (Req 2.6).</li>
 *   <li>{@code fetchedAt} — epoch milliseconds when this policy was fetched, used for the
 *       refresh-interval staleness check (Req 2.7).</li>
 *   <li>{@code status} — whether the policy {@link Status#LOADED loaded} cleanly, was
 *       {@link Status#MISSING missing} (e.g. not-found, Req 2.3), or was
 *       {@link Status#MALFORMED malformed}/unfetchable (Req 2.2).</li>
 * </ul>
 *
 * <p>A {@link Status#MISSING} or {@link Status#MALFORMED} policy is treated by the
 * {@link RobotsManager} as imposing no crawl restrictions (Req 2.2, 2.3): every path is allowed
 * and the effective delay falls back to the configured minimum delay.
 *
 * <p>Instances are immutable; the rule lists are defensively copied to unmodifiable lists.
 */
public record RobotsPolicy(
        String host,
        List<String> disallowRules,
        List<String> allowRules,
        Double crawlDelaySeconds,
        long fetchedAt,
        Status status) {

    /** Load state of a {@link RobotsPolicy} (design §1.2 {@code status}, Req 2.2 / 2.3). */
    public enum Status {
        /** The robots.txt was fetched and parsed successfully. */
        LOADED,
        /** No robots.txt was published (e.g. an HTTP 404); all URLs are allowed (Req 2.3). */
        MISSING,
        /** The robots.txt fetch failed or returned malformed content; no restrictions (Req 2.2). */
        MALFORMED
    }

    /**
     * Canonical constructor: validates and defensively copies the rule lists to unmodifiable lists.
     *
     * @throws NullPointerException     if {@code host}, {@code disallowRules}, {@code allowRules},
     *                                  or {@code status} is null
     * @throws IllegalArgumentException if {@code crawlDelaySeconds} is present and negative
     */
    public RobotsPolicy {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(disallowRules, "disallowRules must not be null");
        Objects.requireNonNull(allowRules, "allowRules must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (crawlDelaySeconds != null && crawlDelaySeconds < 0) {
            throw new IllegalArgumentException("crawlDelaySeconds must not be negative");
        }
        disallowRules = List.copyOf(disallowRules);
        allowRules = List.copyOf(allowRules);
    }

    /**
     * Builds a {@link Status#LOADED} policy from parsed rules.
     *
     * @param host              the governed host
     * @param disallowRules     disallow path prefixes (Req 2.4)
     * @param allowRules        allow path prefixes (Req 2.4)
     * @param crawlDelaySeconds host crawl-delay, or {@code null} if unspecified (Req 2.6)
     * @param fetchedAt         epoch milliseconds when fetched (Req 2.7)
     * @return a loaded {@code RobotsPolicy}
     */
    public static RobotsPolicy loaded(String host,
                                      List<String> disallowRules,
                                      List<String> allowRules,
                                      Double crawlDelaySeconds,
                                      long fetchedAt) {
        return new RobotsPolicy(host, disallowRules, allowRules, crawlDelaySeconds, fetchedAt, Status.LOADED);
    }

    /**
     * Builds a {@link Status#MISSING} policy (no robots.txt published; all allowed, Req 2.3).
     *
     * @param host      the governed host
     * @param fetchedAt epoch milliseconds when the not-found response was observed (Req 2.7)
     * @return a missing-status {@code RobotsPolicy} with no rules and no crawl-delay
     */
    public static RobotsPolicy missing(String host, long fetchedAt) {
        return new RobotsPolicy(host, List.of(), List.of(), null, fetchedAt, Status.MISSING);
    }

    /**
     * Builds a {@link Status#MALFORMED} policy (fetch failed or unparseable; no restrictions,
     * Req 2.2).
     *
     * @param host      the governed host
     * @param fetchedAt epoch milliseconds when the failure was observed (Req 2.7)
     * @return a malformed-status {@code RobotsPolicy} with no rules and no crawl-delay
     */
    public static RobotsPolicy malformed(String host, long fetchedAt) {
        return new RobotsPolicy(host, List.of(), List.of(), null, fetchedAt, Status.MALFORMED);
    }

    /**
     * @return {@code true} when this policy imposes no crawl restrictions — i.e. it is
     * {@link Status#MISSING} or {@link Status#MALFORMED} (Req 2.2, 2.3).
     */
    public boolean hasNoRestrictions() {
        return status == Status.MISSING || status == Status.MALFORMED;
    }
}
