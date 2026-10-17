package com.searchengine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.searchengine.common.DocId;
import com.searchengine.common.ParsedDocument;
import com.searchengine.common.ParseStatus;
import com.searchengine.common.Token;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SegmentBuilder} (design §D, Req 5.1, 5.2). */
class SegmentBuilderTest {

    private static List<Token> tokens(String... texts) {
        return java.util.Arrays.stream(texts).map(Token::of).toList();
    }

    @Test
    void mapsEachTermToAscendingPositions() {
        Segment seg = SegmentBuilder.buildSegment(tokens("the", "cat", "sat", "on", "the", "cat"));

        assertEquals(List.of(0, 4), seg.positions("the"));
        assertEquals(List.of(1, 5), seg.positions("cat"));
        assertEquals(List.of(2), seg.positions("sat"));
        assertEquals(List.of(3), seg.positions("on"));
    }

    @Test
    void positionsAreAscendingForRepeatedTerms() {
        Segment seg = SegmentBuilder.buildSegment(tokens("a", "a", "a"));
        List<Integer> positions = seg.positions("a");
        assertEquals(List.of(0, 1, 2), positions);
        for (int i = 1; i < positions.size(); i++) {
            assertTrue(positions.get(i) > positions.get(i - 1), "positions must be strictly ascending");
        }
    }

    @Test
    void includesEveryDistinctTerm() {
        Segment seg = SegmentBuilder.buildSegment(tokens("alpha", "beta", "alpha", "gamma"));
        assertEquals(java.util.Set.of("alpha", "beta", "gamma"), seg.terms());
        assertEquals(3, seg.termCount());
    }

    @Test
    void emptyDocumentProducesEmptySegment() {
        Segment seg = SegmentBuilder.buildSegment(List.<Token>of());
        assertEquals(0, seg.termCount());
        assertFalse(seg.containsTerm("anything"));
        assertEquals(List.of(), seg.positions("missing"));
    }

    @Test
    void buildsFromParsedDocument() {
        ParsedDocument doc = new ParsedDocument(
                DocId.of(1),
                "https://example.com/",
                "Example",
                tokens("hello", "world", "hello"),
                List.of(),
                "hash",
                ParseStatus.PARSED);

        Segment seg = SegmentBuilder.buildSegment(doc);
        assertEquals(List.of(0, 2), seg.positions("hello"));
        assertEquals(List.of(1), seg.positions("world"));
    }

    @Test
    void isDeterministic() {
        List<Token> in = tokens("x", "y", "x", "z", "y");
        assertEquals(SegmentBuilder.buildSegment(in).asMap(), SegmentBuilder.buildSegment(in).asMap());
    }
}
