package com.searchengine.crawler;

import com.searchengine.common.AddResult;
import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.UrlCanonicalizer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Frontier: the prioritized queue of URLs scheduled to be crawled (design §1.1, LLD §B
 * "Frontier Prioritization").
 *
 * <p>Ordering is the total order {@code (-priority, canonicalUrl)}: entries are drained by
 * descending {@code priority}, breaking ties by ascending Canonical_URL, so that scheduling is
 * deterministic (Req 1.4). {@link #next()} pops the highest-priority entry and removes it from
 * the Frontier (Req 1.5).
 *
 * <p>Membership and dedup are backed by two structures:
 * <ul>
 *   <li>A max-priority queue (a {@link PriorityQueue} ordered by the total key above) holding
 *       the entries awaiting fetch.</li>
 *   <li>A {@link SeenSet} that records every Canonical_URL ever added, and also consults the
 *       Content_Store, so a URL that is already queued or already fetched is never re-added
 *       (Req 1.3, 1.6).</li>
 * </ul>
 *
 * <p>{@link #add(String)} canonicalizes the raw URL (Req 1.2), rejecting anything that is not a
 * syntactically valid absolute URL, and dedups on the resulting Canonical_URL so that URLs which
 * normalize to the same Canonical_URL retain exactly one Frontier entry (Req 1.6).
 *
 * <p>This class is not thread-safe.
 */
public final class Frontier {

    /**
     * Total ordering for the heap: highest {@code priority} first (descending), ties broken by
     * ascending Canonical_URL (Req 1.4). Because {@link CanonicalUrl} imposes a total order over
     * valid values, this comparator yields a deterministic total order over entries.
     */
    private static final Comparator<FrontierEntry> ORDER =
            Comparator.comparingInt(FrontierEntry::priority).reversed()
                    .thenComparing(FrontierEntry::canonicalUrl);

    private final PriorityQueue<FrontierEntry> queue = new PriorityQueue<>(ORDER);

    /**
     * Canonical_URLs currently queued in the Frontier, mapped to their entry. Distinct from the
     * SeenSet: an entry leaves this map when it is popped by {@link #next()} (so the Frontier no
     * longer "contains" it, Req 1.5), whereas the SeenSet remembers it permanently to prevent
     * re-adding an already-seen URL (Req 1.3, 1.6).
     */
    private final Map<CanonicalUrl, FrontierEntry> queued = new HashMap<>();

    /** Records every Canonical_URL ever added plus Content_Store membership (Req 1.3, 1.6). */
    private final SeenSet seenSet;

    /** Creates a Frontier with a default {@link SeenSet} (empty Content_Store). */
    public Frontier() {
        this(new SeenSet());
    }

    /**
     * Creates a Frontier backed by the given {@link SeenSet}. Reuse the SeenSet from task 4.1 so
     * dedup consults both prior adds and Content_Store membership (Req 1.3, 1.6).
     *
     * @param seenSet the seen-set to use; must be non-null
     */
    public Frontier(SeenSet seenSet) {
        this.seenSet = Objects.requireNonNull(seenSet, "seenSet must not be null");
    }

    /**
     * Adds a raw URL to the Frontier with the given scheduling attributes, canonicalizing and
     * deduplicating it (Req 1.1, 1.2, 1.3, 1.6).
     *
     * <p>Resolution:
     * <ol>
     *   <li>Canonicalize; if invalid, return {@link AddResult#INVALID_REJECTED} (Req 1.2).</li>
     *   <li>If the Canonical_URL is already queued in the Frontier, return
     *       {@link AddResult#DUPLICATE_SKIPPED} (Req 1.6).</li>
     *   <li>Else if the SeenSet reports it seen — meaning it was previously added or is already in
     *       the Content_Store — return {@link AddResult#ALREADY_FETCHED} (Req 1.3).</li>
     *   <li>Otherwise enqueue a new entry and return {@link AddResult#ADDED} (Req 1.1).</li>
     * </ol>
     *
     * @param url          the raw URL to add
     * @param priority     scheduling priority (higher = fetched sooner)
     * @param discoveredAt epoch milliseconds when discovered
     * @return the {@link AddResult} describing the outcome
     */
    public AddResult add(String url, int priority, long discoveredAt) {
        CanonicalUrl canonical = UrlCanonicalizer.canonicalize(url);
        if (!canonical.isValid()) {
            return AddResult.INVALID_REJECTED; // Req 1.2
        }
        if (queued.containsKey(canonical)) {
            return AddResult.DUPLICATE_SKIPPED; // Req 1.6 — already in the Frontier
        }
        if (seenSet.contains(canonical)) {
            // Previously added-and-drained, or already fetched into the Content_Store (Req 1.3).
            return AddResult.ALREADY_FETCHED;
        }
        FrontierEntry entry = new FrontierEntry(canonical, priority, 0, hostOf(canonical), discoveredAt);
        enqueue(entry);
        seenSet.add(canonical);
        return AddResult.ADDED; // Req 1.1
    }

    /**
     * Convenience overload that adds a URL with priority {@code 0} and no discovery timestamp.
     *
     * @param url the raw URL to add
     * @return the {@link AddResult} describing the outcome
     */
    public AddResult add(String url) {
        return add(url, 0, 0L);
    }

    /**
     * Removes and returns the highest-priority entry, breaking ties by ascending Canonical_URL
     * (Req 1.4). The returned Canonical_URL is removed from the Frontier so that
     * {@link #contains(CanonicalUrl)} reports {@code false} for it afterwards (Req 1.5). The
     * SeenSet still remembers it, so it is not re-added later (Req 1.3).
     *
     * @return the next entry to fetch, or {@code null} if the Frontier is empty
     */
    public FrontierEntry next() {
        FrontierEntry entry = queue.poll();
        if (entry == null) {
            return null;
        }
        queued.remove(entry.canonicalUrl()); // Req 1.5 — no longer in the Frontier
        return entry;
    }

    /**
     * Reports whether a Canonical_URL currently has an entry in the Frontier. This reflects only
     * entries awaiting fetch; an entry removed by {@link #next()} is no longer contained (Req 1.5).
     *
     * @param canonicalUrl the Canonical_URL to test; must be non-null
     * @return {@code true} if the Frontier currently holds an entry for it
     */
    public boolean contains(CanonicalUrl canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        return queued.containsKey(canonicalUrl);
    }

    /**
     * Re-inserts an entry with an updated retry count (Req 3.2). If an entry for the same
     * Canonical_URL is still queued it is replaced; otherwise the entry is added back to the
     * Frontier for another fetch attempt.
     *
     * @param entry         the entry to reschedule; must be non-null
     * @param newRetryCount the new retry count; must be non-negative
     */
    public void reschedule(FrontierEntry entry, int newRetryCount) {
        Objects.requireNonNull(entry, "entry must not be null");
        CanonicalUrl canonical = entry.canonicalUrl();
        FrontierEntry existing = queued.get(canonical);
        if (existing != null) {
            queue.remove(existing);
        }
        FrontierEntry rescheduled = entry.withRetryCount(newRetryCount);
        enqueue(rescheduled);
        // Ensure the SeenSet remembers it (harmless if already present).
        seenSet.add(canonical);
    }

    /** @return the number of entries currently awaiting fetch in the Frontier. */
    public int size() {
        return queue.size();
    }

    // --- internals --------------------------------------------------------------------------

    private void enqueue(FrontierEntry entry) {
        queue.add(entry);
        queued.put(entry.canonicalUrl(), entry);
    }

    /**
     * Extracts the host from a valid Canonical_URL's normalized string ({@code scheme://host...}).
     * Returns an empty string if the host cannot be located (should not happen for valid URLs).
     */
    private static String hostOf(CanonicalUrl canonical) {
        String value = canonical.value();
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        int hostStart = schemeEnd + 3;
        int hostEnd = value.length();
        for (int i = hostStart; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                hostEnd = i;
                break;
            }
        }
        return value.substring(hostStart, hostEnd);
    }
}
