package com.searchengine.index;

import com.searchengine.common.ParsedDocument;
import com.searchengine.common.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * SegmentBuilder: builds the per-Doc_ID {@link Segment} for a parsed document (design §D
 * "Inverted Index Construction").
 *
 * <p>Implements the design pseudocode:
 * <pre>
 * function buildSegment(doc):
 *     seg = {}                                # term -> ascending positions
 *     for (pos, token) in enumerate(doc.normalizedTokens):
 *         seg[token].append(pos)             # positions naturally ascending (Req 5.2)
 *     return seg                             # one immutable segment per Doc_ID
 * </pre>
 *
 * <p>Because tokens are enumerated left-to-right, each term's positions are appended in
 * increasing order and are therefore already ascending (Req 5.2). Every distinct term the
 * document contains gets an entry (Req 5.1). The builder is pure and deterministic: the same
 * token sequence always yields the same segment.
 */
public final class SegmentBuilder {

    private SegmentBuilder() {
        // static-only utility
    }

    /**
     * Builds the segment for a parsed document by mapping each term to its ascending positions.
     *
     * @param doc the parsed document whose normalized tokens are indexed
     * @return an immutable {@link Segment} of term -> ascending positions
     */
    public static Segment buildSegment(ParsedDocument doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        return buildSegment(doc.normalizedTokens());
    }

    /**
     * Builds a segment directly from an ordered token list. The token at index {@code i} is
     * treated as occurring at position {@code i}, so positions are naturally ascending.
     *
     * @param tokens the document's normalized tokens in reading order
     * @return an immutable {@link Segment} of term -> ascending positions
     */
    public static Segment buildSegment(List<Token> tokens) {
        Objects.requireNonNull(tokens, "tokens must not be null");
        TreeMap<String, List<Integer>> seg = new TreeMap<>();
        int pos = 0;
        for (Token token : tokens) {
            Objects.requireNonNull(token, "token must not be null");
            seg.computeIfAbsent(token.text(), t -> new ArrayList<>()).add(pos);
            pos++;
        }
        return new Segment(seg);
    }
}
