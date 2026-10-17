package com.searchengine.retrieval;

import com.searchengine.common.Posting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phrase / positional matching (design LLD §F, Req 8.2).
 *
 * <p>A document matches a phrase if and only if it contains every token of the phrase, in the
 * specified order, at consecutive term positions with no intervening tokens (no gaps). If any
 * phrase token is missing from the document, the document does not match.
 *
 * <p>The per-document term position lists are those recorded in the postings representation
 * (task 6.1's {@link Posting} positions): each term maps to the ascending term positions at which
 * it occurs within the document.
 */
public final class PhraseMatcher {

    private PhraseMatcher() {
        // static utility
    }

    /**
     * Returns {@code true} iff {@code phraseTokens} occur at consecutive positions, in order, with
     * no gaps, given a single document's term-to-positions view.
     *
     * <p>Matching semantics (Req 8.2):
     * <ul>
     *   <li>An empty phrase trivially matches (there is no constraint to violate).</li>
     *   <li>If any phrase token has no position list in {@code docPositionsByTerm}, the phrase
     *       does not match (a missing token can never appear consecutively).</li>
     *   <li>Otherwise the phrase matches iff there exists a start position {@code p} such that the
     *       i-th phrase token occurs at position {@code p + i} for every {@code i}.</li>
     * </ul>
     *
     * @param docPositionsByTerm the document's term -&gt; ascending positions view (may be empty)
     * @param phraseTokens       the ordered phrase tokens to match
     * @return {@code true} iff the phrase occurs at consecutive positions in order
     */
    public static boolean phraseMatches(Map<String, List<Integer>> docPositionsByTerm,
                                        List<String> phraseTokens) {
        Objects.requireNonNull(docPositionsByTerm, "docPositionsByTerm must not be null");
        Objects.requireNonNull(phraseTokens, "phraseTokens must not be null");

        // An empty phrase imposes no constraint.
        if (phraseTokens.isEmpty()) {
            return true;
        }

        // Gather the position list for each phrase token; any missing token means no match.
        List<Set<Integer>> posSets = new ArrayList<>(phraseTokens.size());
        for (String token : phraseTokens) {
            List<Integer> positions = docPositionsByTerm.get(token);
            if (positions == null || positions.isEmpty()) {
                return false;
            }
            posSets.add(new HashSet<>(positions));
        }

        // For each candidate start position of the first token, check consecutive positions.
        Set<Integer> firstPositions = posSets.get(0);
        for (int start : firstPositions) {
            boolean consecutive = true;
            for (int i = 1; i < posSets.size(); i++) {
                if (!posSets.get(i).contains(start + i)) {
                    consecutive = false;
                    break;
                }
            }
            if (consecutive) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience overload matching the design's posting-oriented view: given the list of
     * {@link Posting}s for each phrase token (each posting being that term's occurrence in a
     * single document), determine whether the phrase occurs at consecutive positions in order.
     *
     * <p>Each element of {@code postingPerToken} corresponds positionally to the token at the same
     * index in {@code phraseTokens}. A {@code null} entry means the term is absent from the
     * document, so the phrase cannot match.
     *
     * @param postingPerToken the per-token {@link Posting} for the document (null if term absent)
     * @return {@code true} iff the phrase occurs at consecutive positions in order
     */
    public static boolean phraseMatches(List<Posting> postingPerToken) {
        Objects.requireNonNull(postingPerToken, "postingPerToken must not be null");

        if (postingPerToken.isEmpty()) {
            return true;
        }

        List<Set<Integer>> posSets = new ArrayList<>(postingPerToken.size());
        for (Posting posting : postingPerToken) {
            if (posting == null || posting.positions().isEmpty()) {
                return false;
            }
            posSets.add(new HashSet<>(posting.positions()));
        }

        Set<Integer> firstPositions = posSets.get(0);
        for (int start : firstPositions) {
            boolean consecutive = true;
            for (int i = 1; i < posSets.size(); i++) {
                if (!posSets.get(i).contains(start + i)) {
                    consecutive = false;
                    break;
                }
            }
            if (consecutive) {
                return true;
            }
        }
        return false;
    }
}
