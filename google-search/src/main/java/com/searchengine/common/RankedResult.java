package com.searchengine.common;

import java.util.Objects;

/**
 * RankedResult: a candidate Document paired with its computed Relevance_Score (design §6, Req 10).
 *
 * <p>The Ranker orders results by descending {@code score}, breaking ties by ascending
 * {@code docId} to produce a strict, deterministic total order (Req 10.2, 10.3, 10.5).
 *
 * @param docId the ranked Document
 * @param score the Relevance_Score in the range [0.0, 1.0] inclusive (Req 10.1)
 */
public record RankedResult(DocId docId, double score) implements Comparable<RankedResult> {

    public RankedResult {
        Objects.requireNonNull(docId, "docId must not be null");
        if (score < 0.0 || score > 1.0 || Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be within [0.0, 1.0], got: " + score);
        }
    }

    /** Total order: descending score, ties broken by ascending Doc_ID (Req 10.2, 10.3). */
    @Override
    public int compareTo(RankedResult other) {
        int byScore = Double.compare(other.score, this.score); // descending score
        if (byScore != 0) {
            return byScore;
        }
        return this.docId.compareTo(other.docId); // ascending Doc_ID
    }
}
