package com.searchengine.retrieval;

import com.searchengine.common.PageStatus;

import java.util.List;
import java.util.Objects;

/**
 * RetrievalResult: the output of {@link CandidateRetrieval#retrieve} (design §6/§E return type
 * {@code { candidates, partial, missingShards }}).
 *
 * <p>Carries the merged candidate set (one entry per Doc_ID, Req 8.3) plus the flags a later
 * serving/failover stage needs (tasks 12.4 / 13 / 22):
 * <ul>
 *   <li>{@code partial} — at least one shard could not be queried (no healthy replica or a shard
 *       deadline was hit), so the candidate set is computed from the responding shards only
 *       (Req 12.4, 13.4).</li>
 *   <li>{@code missingShards} — the count of shards that did not contribute (Req 12.4/13.4 record
 *       the non-responding shard count).</li>
 * </ul>
 *
 * <p>The no-match condition (Req 8.4) is not encoded as an exception: when {@code candidates} is
 * empty, {@link #pageStatus()} maps it to {@link PageStatus#NO_MATCHES} (unless a shard-coverage
 * flag applies), which the Serving_Layer turns into an empty Search_Results_Page.
 *
 * @param candidates    the merged candidates in ascending Doc_ID order, one entry per Doc_ID
 * @param partial       {@code true} iff one or more shards did not contribute (Req 12.4/13.4)
 * @param missingShards the number of shards that did not contribute (&gt;= 0)
 */
public record RetrievalResult(List<Candidate> candidates, boolean partial, int missingShards) {

    public RetrievalResult {
        Objects.requireNonNull(candidates, "candidates must not be null");
        candidates = List.copyOf(candidates);
        if (missingShards < 0) {
            throw new IllegalArgumentException("missingShards must be non-negative, got: " + missingShards);
        }
    }

    /** Returns {@code true} iff no Document satisfied the query constraints (Req 8.4). */
    public boolean noMatches() {
        return candidates.isEmpty();
    }

    /**
     * Maps this result to the Search_Results_Page-level status the Serving_Layer should carry.
     *
     * <ul>
     *   <li>no candidates and no missing shards -&gt; {@link PageStatus#NO_MATCHES} (Req 8.4);</li>
     *   <li>one or more shards missing -&gt; {@link PageStatus#PARTIAL_RESULT} (Req 12.4/13.4),
     *       taking precedence over NO_MATCHES because the empty/partial set is not authoritative;</li>
     *   <li>otherwise -&gt; {@link PageStatus#OK}.</li>
     * </ul>
     *
     * <p>The distinction between a shard timeout (Req 12.4) and full shard loss (Req 13.4) is a
     * serving-layer concern (tasks 13/22); this method reports the shared partial condition and the
     * serving layer refines it using {@link #missingShards()} and replica-health context.
     */
    public PageStatus pageStatus() {
        if (partial || missingShards > 0) {
            return PageStatus.PARTIAL_RESULT;
        }
        return noMatches() ? PageStatus.NO_MATCHES : PageStatus.OK;
    }
}
