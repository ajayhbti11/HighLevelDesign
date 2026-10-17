package com.searchengine.retrieval;

import com.searchengine.common.Posting;

import java.util.List;

/**
 * ShardPostingSource: a per-shard abstraction over posting-list access used by
 * {@link CandidateRetrieval} (design §E "Candidate Retrieval + Shard Merge").
 *
 * <p>The design's {@code retrieve} pseudocode fans out to one healthy replica per shard and calls
 * {@code replica.postings(term)}. Modeling that access behind this interface keeps candidate
 * retrieval unit-testable without a live cluster: tests supply an in-memory implementation, while
 * production wiring can back it with the {@link com.searchengine.index.Indexer} for a resolved
 * shard/replica. Each instance represents the postings visible for a <em>single</em> shard.
 *
 * <p>Implementations must return postings for a term in <b>ascending Doc_ID</b> order (the same
 * ordering guaranteed by {@link com.searchengine.index.PostingList#postings()}), because the
 * AND-intersection in {@link CandidateRetrieval} relies on that ordering. A term that is absent
 * from the shard must return an empty list (never {@code null}).
 */
@FunctionalInterface
public interface ShardPostingSource {

    /**
     * Returns the postings for {@code term} visible on this shard, in ascending Doc_ID order.
     *
     * @param term a normalized query term (byte-identical to index terms, per Req 7.1)
     * @return the postings for the term (ascending Doc_ID), or an empty list if the term is absent
     */
    List<Posting> postings(String term);
}
