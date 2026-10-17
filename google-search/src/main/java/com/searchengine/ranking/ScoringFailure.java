package com.searchengine.ranking;

import com.searchengine.common.DocId;

import java.util.Objects;

/**
 * Records a per-Document scoring failure (Req 10.7).
 *
 * <p>When computing the Relevance_Score for a candidate Document throws, the {@link Ranker}
 * excludes that Document from the results, preserves the ordering of the remaining Documents,
 * and records a {@code ScoringFailure} carrying the offending Doc_ID and the failure cause.
 *
 * @param docId the Document whose scoring failed
 * @param cause the exception thrown while scoring the Document
 */
public record ScoringFailure(DocId docId, Throwable cause) {

    public ScoringFailure {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
    }
}
