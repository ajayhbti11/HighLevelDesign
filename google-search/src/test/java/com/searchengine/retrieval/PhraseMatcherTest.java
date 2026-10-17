package com.searchengine.retrieval;

import com.searchengine.common.DocId;
import com.searchengine.common.Posting;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PhraseMatcher} (Req 8.2): a document matches a phrase iff it contains
 * every phrase token, in order, at consecutive positions with no gaps.
 */
class PhraseMatcherTest {

    // ---- map-based overload --------------------------------------------------------------

    @Test
    void emptyPhraseMatches() {
        assertTrue(PhraseMatcher.phraseMatches(Map.of("a", List.of(0)), List.of()));
        assertTrue(PhraseMatcher.phraseMatches(Map.of(), List.of()));
    }

    @Test
    void singleTokenPresentMatches() {
        assertTrue(PhraseMatcher.phraseMatches(Map.of("hello", List.of(3)), List.of("hello")));
    }

    @Test
    void singleTokenMissingDoesNotMatch() {
        assertFalse(PhraseMatcher.phraseMatches(Map.of("world", List.of(0)), List.of("hello")));
    }

    @Test
    void consecutivePhraseMatches() {
        // "quick brown fox" at positions 0,1,2
        Map<String, List<Integer>> doc = Map.of(
                "quick", List.of(0),
                "brown", List.of(1),
                "fox", List.of(2));
        assertTrue(PhraseMatcher.phraseMatches(doc, List.of("quick", "brown", "fox")));
    }

    @Test
    void nonConsecutivePhraseDoesNotMatch() {
        // tokens present but with a gap: quick@0, brown@2 (missing adjacency), fox@3
        Map<String, List<Integer>> doc = Map.of(
                "quick", List.of(0),
                "brown", List.of(2),
                "fox", List.of(3));
        assertFalse(PhraseMatcher.phraseMatches(doc, List.of("quick", "brown", "fox")));
    }

    @Test
    void wrongOrderDoesNotMatch() {
        // "brown quick" is not present even though both tokens exist consecutively as "quick brown"
        Map<String, List<Integer>> doc = Map.of(
                "quick", List.of(0),
                "brown", List.of(1));
        assertFalse(PhraseMatcher.phraseMatches(doc, List.of("brown", "quick")));
    }

    @Test
    void missingTokenDoesNotMatch() {
        Map<String, List<Integer>> doc = Map.of(
                "quick", List.of(0),
                "brown", List.of(1));
        assertFalse(PhraseMatcher.phraseMatches(doc, List.of("quick", "brown", "fox")));
    }

    @Test
    void matchesAtLaterOccurrence() {
        // "the cat" where "the" appears at 0 and 3, "cat" at 4 -> consecutive at start=3
        Map<String, List<Integer>> doc = Map.of(
                "the", List.of(0, 3),
                "cat", List.of(4));
        assertTrue(PhraseMatcher.phraseMatches(doc, List.of("the", "cat")));
    }

    @Test
    void repeatedTokenPhraseMatches() {
        // phrase "na na" needs consecutive positions for the same token
        assertTrue(PhraseMatcher.phraseMatches(
                Map.of("na", List.of(0, 1, 2)), List.of("na", "na")));
        assertFalse(PhraseMatcher.phraseMatches(
                Map.of("na", List.of(0, 2, 4)), List.of("na", "na")));
    }

    // ---- posting-based overload ----------------------------------------------------------

    @Test
    void postingOverloadConsecutiveMatches() {
        DocId d = DocId.of(1);
        List<Posting> perToken = List.of(
                new Posting(d, List.of(5)),
                new Posting(d, List.of(6)),
                new Posting(d, List.of(7)));
        assertTrue(PhraseMatcher.phraseMatches(perToken));
    }

    @Test
    void postingOverloadGapDoesNotMatch() {
        DocId d = DocId.of(1);
        List<Posting> perToken = List.of(
                new Posting(d, List.of(5)),
                new Posting(d, List.of(7)));
        assertFalse(PhraseMatcher.phraseMatches(perToken));
    }

    @Test
    void postingOverloadEmptyPhraseMatches() {
        assertTrue(PhraseMatcher.phraseMatches(List.of()));
    }
}
