package com.searchengine.index;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Segment: the immutable per-Doc_ID index built for a single parsed document (design §D
 * "Inverted Index Construction", {@code buildSegment(doc)}).
 *
 * <p>Maps each distinct term to its ascending term positions within that one document
 * (Req 5.1, 5.2). This mirrors the design's {@code seg = {}  # term -> ascending positions}
 * and is the unit that the Indexer later merges into per-shard posting lists and swaps
 * atomically (Req 5.4). Terms are held in a {@link TreeMap} for deterministic iteration.
 */
public final class Segment {

    /** term -> ascending positions within the document. */
    private final SortedMap<String, List<Integer>> termPositions;

    Segment(SortedMap<String, List<Integer>> termPositions) {
        // Deep-copy into an immutable structure to keep the segment immutable.
        TreeMap<String, List<Integer>> copy = new TreeMap<>();
        for (var entry : termPositions.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.termPositions = java.util.Collections.unmodifiableSortedMap(copy);
    }

    /** All distinct terms in this document, in ascending term order. */
    public java.util.Set<String> terms() {
        return termPositions.keySet();
    }

    /**
     * Ascending term positions of {@code term} within the document, or an empty list when the
     * term does not appear.
     */
    public List<Integer> positions(String term) {
        return termPositions.getOrDefault(term, List.of());
    }

    /** Returns {@code true} iff the document contains {@code term}. */
    public boolean containsTerm(String term) {
        return termPositions.containsKey(term);
    }

    /** Number of distinct terms in the document. */
    public int termCount() {
        return termPositions.size();
    }

    /** Immutable view of the underlying term -> ascending positions map. */
    public Map<String, List<Integer>> asMap() {
        return termPositions;
    }

    @Override
    public String toString() {
        return "Segment" + termPositions;
    }
}
