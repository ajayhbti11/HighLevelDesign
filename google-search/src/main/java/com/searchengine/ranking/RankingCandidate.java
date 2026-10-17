package com.searchengine.ranking;

import com.searchengine.common.DocId;

import java.util.Objects;

/**
 * A candidate Document presented to the {@link Ranker} together with its precomputed,
 * normalized scoring components (design §G "Ranking").
 *
 * <p>The two components are the term-relevance component ({@code bm25Norm}, a bounded/squashed
 * BM25 value) and the document-authority component ({@code authorityNorm}, min-max normalized
 * against the corpus). Both are required to lie in {@code [0.0, 1.0]} so that the blended score
 * {@code w_r*bm25Norm + w_a*authorityNorm} (with {@code w_r + w_a = 1}) also lies in
 * {@code [0.0, 1.0]} (Req 10.1, 10.4).
 *
 * @param docId         the candidate Document
 * @param bm25Norm      normalized term-relevance component in {@code [0.0, 1.0]}
 * @param authorityNorm normalized document-authority component in {@code [0.0, 1.0]}
 */
public record RankingCandidate(DocId docId, double bm25Norm, double authorityNorm) {

    public RankingCandidate {
        Objects.requireNonNull(docId, "docId must not be null");
        requireUnitInterval(bm25Norm, "bm25Norm");
        requireUnitInterval(authorityNorm, "authorityNorm");
    }

    private static void requireUnitInterval(double v, String name) {
        if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0], got: " + v);
        }
    }
}
