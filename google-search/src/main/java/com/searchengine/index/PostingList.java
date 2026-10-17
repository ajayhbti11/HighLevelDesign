package com.searchengine.index;

import com.searchengine.common.DocId;
import com.searchengine.common.Posting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Posting_List: the ordered list of {@link Posting} entries for a single term (design §4,
 * data-structures table; Req 5.1, 5.2).
 *
 * <p>Invariants maintained by this structure:
 * <ul>
 *   <li>Postings are kept ordered by <b>ascending Doc_ID</b> (design "Posting / Posting_List"
 *       definition).</li>
 *   <li>Each {@link Posting}'s positions are <b>ascending</b> (enforced by {@code Posting} /
 *       {@code SegmentBuilder}).</li>
 *   <li>At most one {@link Posting} exists per Doc_ID; adding positions for an existing Doc_ID
 *       merges them.</li>
 * </ul>
 *
 * <p>The in-memory representation uses a {@link TreeMap} keyed by Doc_ID so ascending order is
 * intrinsic and lookups are logarithmic. For on-disk storage the design calls for delta-encoded
 * ascending Doc_IDs plus varint, and delta-encoded ascending positions (see
 * {@link VarintCodec}); the ascending ordering guaranteed here is exactly what makes that
 * delta/varint encoding effective. A straightforward in-memory representation is used for now.
 */
public final class PostingList {

    /** Doc_ID -> ascending positions for that document. TreeMap keeps Doc_IDs ascending. */
    private final TreeMap<DocId, List<Integer>> byDocId = new TreeMap<>();

    /**
     * Adds a term occurrence for the given Doc_ID at the given (ascending) positions.
     * Positions for a Doc_ID already present are merged and re-sorted so the stored positions
     * remain ascending (Req 5.2).
     *
     * @param docId     the document containing the term
     * @param positions ascending term positions of the term within the document
     */
    public void addPosting(DocId docId, List<Integer> positions) {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(positions, "positions must not be null");
        List<Integer> existing = byDocId.computeIfAbsent(docId, d -> new ArrayList<>());
        existing.addAll(positions);
        Collections.sort(existing);
    }

    /**
     * Removes every entry referencing the given Doc_ID (supports Req 5.5 removal semantics).
     *
     * @return {@code true} if an entry was present and removed
     */
    public boolean removeDoc(DocId docId) {
        return byDocId.remove(docId) != null;
    }

    /** Returns {@code true} iff this list contains a posting for the given Doc_ID. */
    public boolean contains(DocId docId) {
        return byDocId.containsKey(docId);
    }

    /** Number of distinct documents (postings) in this list. */
    public int size() {
        return byDocId.size();
    }

    public boolean isEmpty() {
        return byDocId.isEmpty();
    }

    /**
     * Returns the postings in ascending Doc_ID order. Each returned {@link Posting} carries an
     * immutable ascending-positions list.
     */
    public List<Posting> postings() {
        List<Posting> result = new ArrayList<>(byDocId.size());
        for (var entry : byDocId.entrySet()) {
            result.add(new Posting(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    @Override
    public String toString() {
        return "PostingList" + postings();
    }
}
