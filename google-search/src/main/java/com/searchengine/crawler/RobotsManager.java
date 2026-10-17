package com.searchengine.crawler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Robots Manager: enforces host politeness and robots compliance (design §1.2, Requirement 2).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #ensurePolicy(String)} — fetch and cache a host's {@link RobotsPolicy} before any
 *       content URL is fetched for that host (Req 2.1), and re-fetch it once the cached record is
 *       older than the configured refresh interval (Req 2.7).</li>
 *   <li>{@link #isAllowed(String)} — decide whether a URL may be fetched using a longest-match
 *       allow/disallow rule comparison (Req 2.4).</li>
 *   <li>{@link #effectiveDelaySeconds(String)} — compute the polite inter-request delay as
 *       {@code clamp(max(minDelay, crawlDelay), 0.1, 60)} seconds (Req 2.5, 2.6).</li>
 * </ul>
 *
 * <p>A {@link RobotsPolicy.Status#MISSING} (not-found, Req 2.3) or
 * {@link RobotsPolicy.Status#MALFORMED} (fetch failure/malformed, Req 2.2) policy is treated as
 * imposing no restrictions: every path is allowed and the effective delay falls back to the
 * configured minimum delay. Those conditions are recorded through the injected recorder.
 *
 * <p>The robots fetch is performed behind an injectable {@link RobotsPolicyFetcher}, and the clock
 * behind an injectable {@link LongSupplier}, so the manager is fully unit-testable without network
 * or wall-clock dependencies. This class is not thread-safe.
 */
public final class RobotsManager {

    /** Absolute lower clamp bound for the effective delay, in seconds (Req 2.5). */
    public static final double MIN_DELAY_CLAMP_SECONDS = 0.1;
    /** Absolute upper clamp bound for the effective delay, in seconds (Req 2.5). */
    public static final double MAX_DELAY_CLAMP_SECONDS = 60.0;

    private final RobotsPolicyFetcher fetcher;
    private final double configuredMinDelaySeconds;
    private final long refreshIntervalMillis;
    private final LongSupplier clock;
    private final Consumer<String> conditionRecorder;

    /** Host -> most recently fetched policy (Req 2.1 caching, Req 2.7 staleness check). */
    private final Map<String, RobotsPolicy> cache = new HashMap<>();

    /**
     * Creates a Robots Manager.
     *
     * @param fetcher                   injectable robots.txt source (network seam); must be non-null
     * @param configuredMinDelaySeconds the configured minimum inter-request delay, in the
     *                                   {@code [0.1, 60]} range (Req 2.5); values outside are clamped
     *                                   when computing the effective delay
     * @param refreshIntervalSeconds    the robots refresh interval in seconds; a cached policy older
     *                                   than this is re-fetched (Req 2.7); must be non-negative
     * @param clock                     supplies the current time in epoch milliseconds (injectable
     *                                   for testing); must be non-null
     * @param conditionRecorder         records missing/malformed conditions (Req 2.2, 2.3); may be
     *                                   {@code null} to disable recording
     * @throws NullPointerException     if {@code fetcher} or {@code clock} is null
     * @throws IllegalArgumentException if {@code refreshIntervalSeconds} is negative
     */
    public RobotsManager(RobotsPolicyFetcher fetcher,
                         double configuredMinDelaySeconds,
                         double refreshIntervalSeconds,
                         LongSupplier clock,
                         Consumer<String> conditionRecorder) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (refreshIntervalSeconds < 0) {
            throw new IllegalArgumentException("refreshIntervalSeconds must be non-negative");
        }
        this.configuredMinDelaySeconds = configuredMinDelaySeconds;
        this.refreshIntervalMillis = (long) (refreshIntervalSeconds * 1000.0);
        this.conditionRecorder = conditionRecorder;
    }

    /**
     * Ensures a {@link RobotsPolicy} is available for {@code host}, fetching it if it is absent or
     * stale (Req 2.1, 2.7).
     *
     * <p>Behavior:
     * <ol>
     *   <li>If a cached policy exists and its age ({@code now - fetchedAt}) does not exceed the
     *       configured refresh interval, the cached policy is returned unchanged (Req 2.7).</li>
     *   <li>Otherwise the {@link RobotsPolicyFetcher} is invoked. A thrown exception is treated as a
     *       malformed fetch (Req 2.2). {@link RobotsPolicy.Status#MISSING} and
     *       {@link RobotsPolicy.Status#MALFORMED} outcomes are recorded via the condition recorder
     *       (Req 2.2, 2.3).</li>
     * </ol>
     *
     * @param host the host to ensure a policy for; must be non-null
     * @return the fresh {@link RobotsPolicy} for the host (never {@code null})
     */
    public RobotsPolicy ensurePolicy(String host) {
        Objects.requireNonNull(host, "host must not be null");
        RobotsPolicy cached = cache.get(host);
        if (cached != null && !isStale(cached)) {
            return cached; // Req 2.7: still fresh, no re-fetch.
        }
        RobotsPolicy fetched = fetchSafely(host);
        cache.put(host, fetched);
        recordConditionIfNeeded(fetched);
        return fetched;
    }

    /**
     * Decides whether {@code url} may be fetched under its host's robots policy, using a
     * longest-match comparison of allow and disallow rules (Req 2.4).
     *
     * <p>The policy is obtained via {@link #ensurePolicy(String)} (fetching/refreshing as needed).
     * If the policy has no restrictions (missing or malformed), every URL is allowed (Req 2.2, 2.3).
     * Otherwise the request path is compared against every rule that is a prefix of the path; the
     * decision follows the {@link #longestMatchAllows(RobotsPolicy, String) longest-matching} rule,
     * with a matching allow rule winning over a matching disallow rule of the same length.
     *
     * @param url the absolute URL to test; must be non-null
     * @return {@code true} if the URL is allowed to be fetched, {@code false} if disallowed
     */
    public boolean isAllowed(String url) {
        Objects.requireNonNull(url, "url must not be null");
        String host = extractHost(url);
        String path = extractPath(url);
        RobotsPolicy policy = ensurePolicy(host);
        if (policy.hasNoRestrictions()) {
            return true; // Req 2.2, 2.3
        }
        return longestMatchAllows(policy, path); // Req 2.4
    }

    /**
     * Computes the effective inter-request delay for {@code host} as
     * {@code clamp(max(configuredMinDelay, crawlDelay), 0.1, 60)} seconds (Req 2.5, 2.6).
     *
     * <p>The policy is obtained via {@link #ensurePolicy(String)}. When the policy specifies no
     * crawl-delay (or has no restrictions), only the configured minimum delay participates, so the
     * result equals the clamped configured minimum delay. The result is never below the configured
     * minimum delay (subject to the {@code [0.1, 60]} clamp).
     *
     * @param host the host to compute the delay for; must be non-null
     * @return the effective delay in seconds, within {@code [0.1, 60]}
     */
    public double effectiveDelaySeconds(String host) {
        Objects.requireNonNull(host, "host must not be null");
        RobotsPolicy policy = ensurePolicy(host);
        double crawlDelay = policy.crawlDelaySeconds() == null ? 0.0 : policy.crawlDelaySeconds();
        double chosen = Math.max(configuredMinDelaySeconds, crawlDelay); // Req 2.6
        return clamp(chosen, MIN_DELAY_CLAMP_SECONDS, MAX_DELAY_CLAMP_SECONDS); // Req 2.5
    }

    // --- internals --------------------------------------------------------------------------

    /** @return {@code true} if the cached policy's age exceeds the configured refresh interval. */
    private boolean isStale(RobotsPolicy policy) {
        long age = clock.getAsLong() - policy.fetchedAt();
        return age > refreshIntervalMillis; // Req 2.7
    }

    /**
     * Invokes the injected fetcher, converting a thrown exception or a {@code null} return into a
     * {@link RobotsPolicy.Status#MALFORMED} policy so callers always receive a usable policy
     * (Req 2.2).
     */
    private RobotsPolicy fetchSafely(String host) {
        try {
            RobotsPolicy fetched = fetcher.fetch(host);
            if (fetched == null) {
                return RobotsPolicy.malformed(host, clock.getAsLong());
            }
            return fetched;
        } catch (RuntimeException e) {
            return RobotsPolicy.malformed(host, clock.getAsLong());
        }
    }

    /** Records a missing (Req 2.3) or malformed (Req 2.2) condition through the recorder, if any. */
    private void recordConditionIfNeeded(RobotsPolicy policy) {
        if (conditionRecorder == null || !policy.hasNoRestrictions()) {
            return;
        }
        String reason = policy.status() == RobotsPolicy.Status.MISSING
                ? "missing robots policy (all allowed)"   // Req 2.3
                : "malformed/failed robots fetch (no restrictions)"; // Req 2.2
        try {
            conditionRecorder.accept("RobotsManager: " + reason + " for host " + policy.host());
        } catch (RuntimeException ignored) {
            // Recording must never abort crawling (aligns with Req 15.6 non-aborting recording).
        }
    }

    /**
     * Longest-match allow/disallow decision (Req 2.4). Considers every allow/disallow rule that is a
     * prefix of {@code path}. Returns {@code true} (allowed) when there is no matching disallow rule,
     * or when the longest matching allow rule is at least as long as the longest matching disallow
     * rule; returns {@code false} otherwise.
     */
    private static boolean longestMatchAllows(RobotsPolicy policy, String path) {
        int longestDisallow = longestMatchingRuleLength(policy.disallowRules(), path);
        if (longestDisallow < 0) {
            return true; // no disallow rule matches -> allowed
        }
        int longestAllow = longestMatchingRuleLength(policy.allowRules(), path);
        // An equal-or-longer allow rule wins over the disallow rule (explicit allow, Req 2.4).
        return longestAllow >= longestDisallow;
    }

    /**
     * @return the length of the longest rule in {@code rules} that is a prefix of {@code path}, or
     * {@code -1} if none matches. An empty-string rule matches every path (length 0).
     */
    private static int longestMatchingRuleLength(java.util.List<String> rules, String path) {
        int longest = -1;
        for (String rule : rules) {
            if (rule != null && path.startsWith(rule) && rule.length() > longest) {
                longest = rule.length();
            }
        }
        return longest;
    }

    /** Clamps {@code value} to the inclusive range {@code [lo, hi]}. */
    private static double clamp(double value, double lo, double hi) {
        if (value < lo) {
            return lo;
        }
        return Math.min(value, hi);
    }

    /**
     * Extracts the host component from an absolute URL string ({@code scheme://host[:port]/path...}).
     * Falls back to the whole input when no scheme separator is present.
     */
    private static String extractHost(String url) {
        int schemeEnd = url.indexOf("://");
        int hostStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int hostEnd = url.length();
        for (int i = hostStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                hostEnd = i;
                break;
            }
        }
        return url.substring(hostStart, hostEnd);
    }

    /**
     * Extracts the path component from an absolute URL string, defaulting to {@code "/"} when the
     * URL has no explicit path. The query and fragment are excluded, matching the rule-prefix
     * comparison used by {@link #longestMatchAllows(RobotsPolicy, String)}.
     */
    private static String extractPath(String url) {
        int schemeEnd = url.indexOf("://");
        int cursor = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int pathStart = -1;
        for (int i = cursor; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/') {
                pathStart = i;
                break;
            }
            if (c == '?' || c == '#') {
                return "/"; // authority ends without a path
            }
        }
        if (pathStart < 0) {
            return "/";
        }
        int pathEnd = url.length();
        for (int i = pathStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                pathEnd = i;
                break;
            }
        }
        String path = url.substring(pathStart, pathEnd);
        return path.isEmpty() ? "/" : path;
    }
}
