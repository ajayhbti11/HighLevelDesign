package com.searchengine.ranking;

/**
 * Computes the raw (pre-clamp) Relevance_Score contribution for a single candidate
 * (design §G, Req 10.1, 10.4).
 *
 * <p>Implementations may throw for a given candidate; the {@link Ranker} treats a thrown
 * exception as a per-Document scoring failure (Req 10.7), excluding that Document and
 * recording the failure while preserving the ordering of the rest.
 */
@FunctionalInterface
public interface ScoringFunction {

    /**
     * Returns a score for the candidate. May throw to signal a scoring failure for this
     * Document (Req 10.7). The value is subsequently clamped to {@code [0.0, 1.0]} by the Ranker.
     *
     * @param candidate the candidate with its normalized components
     * @return the computed score (typically already within {@code [0,1]})
     */
    double score(RankingCandidate candidate);
}
