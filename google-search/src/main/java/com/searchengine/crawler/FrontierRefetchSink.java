package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.DocId;

import java.util.Objects;

/**
 * A {@link RefetchSink} that enqueues due re-fetches into the {@link Frontier} (design §1.4:
 * {@code SCHED --> FRONTIER}). Reuses {@link Frontier#add(String, int, long)} so canonicalization
 * and dedup (Req 1.2, 1.3, 1.6) apply uniformly to re-fetches, with the due time carried as the
 * entry's discovery timestamp.
 *
 * <p>This adapter is the production wiring between the Re-fetch Scheduler and the Frontier; it holds
 * no per-Document state of its own.
 */
public final class FrontierRefetchSink implements RefetchSink {

    private final Frontier frontier;
    private final int refetchPriority;

    /**
     * Creates a sink that enqueues re-fetches into the given Frontier with priority {@code 0}.
     *
     * @param frontier the Frontier to feed; must be non-null
     */
    public FrontierRefetchSink(Frontier frontier) {
        this(frontier, 0);
    }

    /**
     * Creates a sink that enqueues re-fetches into the given Frontier with the given priority.
     *
     * @param frontier        the Frontier to feed; must be non-null
     * @param refetchPriority the scheduling priority to assign each enqueued re-fetch
     */
    public FrontierRefetchSink(Frontier frontier, int refetchPriority) {
        this.frontier = Objects.requireNonNull(frontier, "frontier must not be null");
        this.refetchPriority = refetchPriority;
    }

    @Override
    public void enqueue(DocId docId, CanonicalUrl canonicalUrl, long at) {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        if (!canonicalUrl.isValid()) {
            throw new IllegalArgumentException("cannot schedule a re-fetch for an INVALID Canonical_URL");
        }
        frontier.add(canonicalUrl.value(), refetchPriority, at);
    }
}
