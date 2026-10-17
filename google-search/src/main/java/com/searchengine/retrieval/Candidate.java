package com.searchengine.retrieval;

import com.searchengine.common.DocId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Candidate: a Document that satisfies a query's required-term (and phrase) constraints, together
 * with the term positions that matched it (design §6 {@code interface Candidate}).
 *
 * <p>{@code matchedPositions} maps each required query term to the ascending positions at which it
 * occurs in this Document. The positions are what the phrase filter (Req 8.2) consumes and what a
 * later ranking stage can use for term-relevance scoring.
 *
 * <p>Equality is by {@code docId} only. This is deliberate: the shard-union merge (Req 8.3) must
 * retain exactly one entry per Doc_ID, so treating two candidates with the same Doc_ID as equal
 * lets standard set/map de-duplication enforce that invariant regardless of matched-position
 * differences that could arise across shard views.
 *
 * @param docId            the candidate Document
 * @param matchedPositions required term -&gt; ascending positions of that term within the Document
 */
public record Candidate(DocId docId, Map<String, List<Integer>> matchedPositions) {

    public Candidate {
        Objects.requireNonNull(docId, "docId must not be null");
        // Deep, deterministic, immutable copy: sorted term keys, ascending immutable position lists.
        Map<String, List<Integer>> copy = new TreeMap<>();
        if (matchedPositions != null) {
            for (var entry : matchedPositions.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        matchedPositions = Map.copyOf(copy);
    }

    /** Convenience factory for a candidate carrying no matched-position detail. */
    public static Candidate of(DocId docId) {
        return new Candidate(docId, Map.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Candidate other)) {
            return false;
        }
        // One entry per Doc_ID (Req 8.3): identity is the Doc_ID alone.
        return docId.equals(other.docId);
    }

    @Override
    public int hashCode() {
        return docId.hashCode();
    }
}
