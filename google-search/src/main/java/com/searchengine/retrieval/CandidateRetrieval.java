package com.searchengine.retrieval;

import com.searchengine.common.DocId;
import com.searchengine.common.Posting;
import com.searchengine.query.QueryNode;
import com.searchengine.query.StructuredQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Candidate_Retrieval + Shard Merge (design §6 {@code CandidateRetrieval}; LLD §E; Req 8.1–8.4).
 *
 * <p>{@link #retrieve} implements the design's algorithm:
 * <pre>
 *   required = requiredTerms(q)
 *   perShard = forEach(shard):
 *       lists = [shard.postings(t) for t in required]
 *       cands = intersectByDocId(lists)        # AND semantics (Req 8.1)
 *       if hasPhrase(q): cands = filterPhrase(cands, q)   # Req 8.2
 *   merged = unionByDocId(perShard)            # one entry per Doc_ID (Req 8.3)
 *   return { candidates: merged, partial, missing }
 * </pre>
 *
 * <p>Design fidelity notes:
 * <ul>
 *   <li><b>AND semantics (Req 8.1).</b> A Document is a candidate iff it appears in the posting
 *       list of <em>every</em> required term. Implemented as an intersection by Doc_ID.</li>
 *   <li><b>Phrase filter (Req 8.2).</b> When the query carries phrase constraints, a surviving
 *       candidate is kept only if every phrase matches consecutively via
 *       {@link PhraseMatcher#phraseMatches(Map, List)}.</li>
 *   <li><b>Shard union (Req 8.3).</b> Per-shard candidate sets are unioned retaining exactly one
 *       {@link Candidate} per Doc_ID, ordered by ascending Doc_ID for deterministic output.</li>
 *   <li><b>No match (Req 8.4).</b> An empty merged set is not an error; the returned
 *       {@link RetrievalResult} reports it via {@link RetrievalResult#pageStatus()} =
 *       {@code NO_MATCHES}.</li>
 *   <li><b>Partial / missing shards.</b> A shard whose posting source is unavailable (its supplier
 *       yields {@code null}, modeling "no healthy replica" / shard deadline) is skipped and counted
 *       so the flags flow to later serving/failover integration (tasks 12.4 / 13 / 22).</li>
 * </ul>
 *
 * <p>Retrieval is a pure, deterministic function of the query and the supplied per-shard posting
 * sources, which keeps it unit-testable without a live cluster.
 */
public final class CandidateRetrieval {

    private CandidateRetrieval() {
        // stateless utility
    }

    /**
     * Retrieves candidates for {@code query} across the given per-shard posting sources.
     *
     * <p>Each element of {@code shardSources} is a {@link Supplier} that resolves the posting source
     * for one shard. A supplier that returns {@code null} models a shard with no healthy replica (or
     * a shard that missed its deadline): that shard is skipped, {@code partial} becomes {@code true},
     * and {@code missingShards} is incremented (Req 12.4/13.4). This indirection lets callers plug in
     * replica selection / deadline handling without this method depending on a live cluster.
     *
     * @param query        the structured query (its required terms and phrases drive retrieval)
     * @param shardSources one supplier per shard resolving that shard's posting source (null =&gt; missing)
     * @return the merged candidates plus partial/missing-shard flags (Req 8.1–8.4)
     */
    public static RetrievalResult retrieve(StructuredQuery query, List<Supplier<ShardPostingSource>> shardSources) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(shardSources, "shardSources must not be null");

        List<String> required = requiredTerms(query);
        List<List<String>> phrases = phraseConstraints(query);

        // A query with no required terms yields no candidates (nothing to intersect).
        if (required.isEmpty()) {
            return new RetrievalResult(List.of(), false, 0);
        }

        // Merge across shards retaining exactly one Candidate per Doc_ID (Req 8.3). LinkedHashMap
        // keeps first-seen order; a final ascending-Doc_ID sort makes the output deterministic.
        Map<DocId, Candidate> merged = new LinkedHashMap<>();
        boolean partial = false;
        int missingShards = 0;

        for (Supplier<ShardPostingSource> supplier : shardSources) {
            ShardPostingSource shard = supplier == null ? null : supplier.get();
            if (shard == null) {
                // No healthy replica / shard did not respond: degrade to partial (Req 12.4/13.4).
                partial = true;
                missingShards++;
                continue;
            }
            for (Candidate candidate : retrieveFromShard(shard, required, phrases)) {
                merged.putIfAbsent(candidate.docId(), candidate); // one entry per Doc_ID (Req 8.3)
            }
        }

        List<Candidate> ordered = new ArrayList<>(merged.values());
        ordered.sort((a, b) -> a.docId().compareTo(b.docId())); // ascending Doc_ID, deterministic
        return new RetrievalResult(ordered, partial, missingShards);
    }

    // --- per-shard retrieval -----------------------------------------------------------------

    /**
     * Retrieves the candidates for one shard: AND-intersect required-term posting lists (Req 8.1),
     * then apply any phrase filter (Req 8.2).
     */
    private static List<Candidate> retrieveFromShard(ShardPostingSource shard,
                                                     List<String> required,
                                                     List<List<String>> phrases) {
        // For each required term, index its postings on this shard by Doc_ID. A required term absent
        // from the shard yields an empty map, so the intersection below is empty (correct AND).
        List<Map<DocId, Posting>> perTerm = new ArrayList<>(required.size());
        for (String term : required) {
            List<Posting> postings = shard.postings(term);
            Map<DocId, Posting> byDocId = new TreeMap<>();
            if (postings != null) {
                for (Posting p : postings) {
                    byDocId.put(p.docId(), p);
                }
            }
            if (byDocId.isEmpty()) {
                // AND with an empty set is empty: no candidate on this shard can satisfy the query.
                return List.of();
            }
            perTerm.add(byDocId);
        }

        // Intersect by Doc_ID (Req 8.1). Drive the scan from the smallest term list for efficiency.
        Map<DocId, Posting> smallest = perTerm.get(0);
        for (Map<DocId, Posting> m : perTerm) {
            if (m.size() < smallest.size()) {
                smallest = m;
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (DocId docId : smallest.keySet()) {
            boolean inAll = true;
            for (Map<DocId, Posting> m : perTerm) {
                if (!m.containsKey(docId)) {
                    inAll = false;
                    break;
                }
            }
            if (!inAll) {
                continue;
            }

            // Collect matched positions for this Doc_ID across all required terms.
            Map<String, List<Integer>> matchedPositions = new TreeMap<>();
            for (int i = 0; i < required.size(); i++) {
                matchedPositions.put(required.get(i), perTerm.get(i).get(docId).positions());
            }

            // Phrase filter (Req 8.2): the Document must contain every phrase consecutively.
            if (satisfiesPhrases(matchedPositions, phrases)) {
                candidates.add(new Candidate(docId, matchedPositions));
            }
        }
        return candidates;
    }

    /** True iff every phrase constraint matches consecutively given the document's positions. */
    private static boolean satisfiesPhrases(Map<String, List<Integer>> docPositionsByTerm,
                                            List<List<String>> phrases) {
        for (List<String> phrase : phrases) {
            if (!PhraseMatcher.phraseMatches(docPositionsByTerm, phrase)) {
                return false;
            }
        }
        return true;
    }

    // --- query decomposition -----------------------------------------------------------------

    /**
     * The distinct required terms of a query: every {@link QueryNode.Term} token plus every token
     * appearing inside a {@link QueryNode.Phrase} (a phrase's tokens are required for the phrase to
     * match). Order is preserved and duplicates removed, so the intersection is over a minimal set.
     *
     * <p>Operators are not expanded here: the design's retrieval algorithm intersects the required
     * terms (default AND between bare terms, Req 8.1). Richer operator semantics (OR/NOT) are a
     * later serving-layer concern; the phrase and bare-term terms captured here are exactly the
     * constraints Req 8.1/8.2 require a candidate to satisfy.
     */
    private static List<String> requiredTerms(StructuredQuery query) {
        // LinkedHashMap-backed distinct-preserving accumulation.
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (QueryNode node : query.nodes()) {
            if (node instanceof QueryNode.Term term) {
                seen.putIfAbsent(term.token(), Boolean.TRUE);
            } else if (node instanceof QueryNode.Phrase phrase) {
                for (String token : phrase.tokens()) {
                    seen.putIfAbsent(token, Boolean.TRUE);
                }
            }
            // Operators contribute no standalone required term here.
        }
        return new ArrayList<>(seen.keySet());
    }

    /** The ordered phrase constraints of a query (Req 8.2). */
    private static List<List<String>> phraseConstraints(StructuredQuery query) {
        List<List<String>> phrases = new ArrayList<>();
        for (QueryNode node : query.nodes()) {
            if (node instanceof QueryNode.Phrase phrase) {
                phrases.add(phrase.tokens());
            }
        }
        return phrases;
    }
}
