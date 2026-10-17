package com.searchengine.index;

import com.searchengine.common.DocId;
import com.searchengine.common.ParsedDocument;
import com.searchengine.common.Posting;
import com.searchengine.common.ShardId;
import com.searchengine.controlplane.ConsistentHashShardMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Indexer: builds, atomically replaces, and deletes inverted-index postings (design §4 "Indexer
 * subsystem"; LLD §D "Inverted Index Construction"; Key Data Structures "per-Doc_ID immutable
 * segment + pointer table").
 *
 * <p>Responsibilities and the acceptance criteria they satisfy:
 * <ul>
 *   <li>{@link #index(ParsedDocument)} - assigns a unique Doc_ID from the {@link DocIdAllocator}
 *       and populates posting lists / positions for every term the document contains
 *       (Req 5.1, 5.2, 5.3).</li>
 *   <li>{@link #reindex(ParsedDocument)} - builds a new per-Doc_ID {@link Segment} and swaps the
 *       owning shard's pointer table in a single atomic pointer swap under a per-shard lock, so no
 *       query ever observes a mixture of prior and current postings for that Doc_ID (Req 5.4).</li>
 *   <li>{@link #remove(DocId)} - deletes every posting referencing the Doc_ID (Req 5.5), also used
 *       to drop documents that return 404/410 on re-fetch (Req 6.2).</li>
 *   <li>On failure after a partial update, the new segment/table is discarded and the shard reverts
 *       to its prior published state, recording the failure (Req 5.6).</li>
 *   <li>{@link #shardFor(DocId)} routes each Doc_ID to exactly one shard via the control plane's
 *       {@link ConsistentHashShardMap} (Req 5.7).</li>
 * </ul>
 *
 * <h2>Atomicity model (Req 5.4)</h2>
 * Each shard keeps its posting lists in an immutable snapshot ({@code Map<term, PostingList>})
 * referenced by an {@link AtomicReference} pointer table. A mutation:
 * <ol>
 *   <li>takes the shard's lock (serializes writers to that shard),</li>
 *   <li>builds a brand-new snapshot off a deep copy of the current one (the published snapshot is
 *       never mutated in place),</li>
 *   <li>publishes it with a single {@link AtomicReference#set(Object)} pointer swap.</li>
 * </ol>
 * Readers dereference the pointer once and see either the entire prior snapshot or the entire new
 * one, never a mixture. If any step throws before the swap, the pointer is left untouched and the
 * shard reflects its prior state (Req 5.6).
 */
public final class Indexer {

    /** A single shard's atomically-swappable pointer table of {@code term -> PostingList}. */
    private static final class ShardIndex {
        /** Lock serializing writers to this shard so pointer swaps do not race (Req 5.4). */
        private final Object lock = new Object();
        /** The currently published immutable snapshot. Swapped atomically on each mutation. */
        private final AtomicReference<Map<String, PostingList>> pointerTable =
                new AtomicReference<>(Map.of());

        Map<String, PostingList> snapshot() {
            return pointerTable.get();
        }
    }

    private final DocIdAllocator docIdAllocator;
    private final ConsistentHashShardMap shardMap;
    private final Map<ShardId, ShardIndex> shards = new ConcurrentHashMap<>();

    /**
     * Optional failure sink for recording indexing failures (Req 5.6). May be {@code null}; when
     * present it is invoked with a human-readable cause whenever an operation reverts on failure.
     * The Observability subsystem can supply this hook in later wiring.
     */
    private final Consumer<String> failureRecorder;

    /**
     * Creates an Indexer over the given allocator and shard map.
     *
     * @param docIdAllocator monotonic unique Doc_ID allocator (Req 5.3)
     * @param shardMap       consistent-hash Doc_ID -> Shard map (Req 5.7)
     */
    public Indexer(DocIdAllocator docIdAllocator, ConsistentHashShardMap shardMap) {
        this(docIdAllocator, shardMap, null);
    }

    /**
     * Creates an Indexer with a failure recorder for indexing-failure reverts (Req 5.6).
     *
     * @param docIdAllocator  monotonic unique Doc_ID allocator (Req 5.3)
     * @param shardMap        consistent-hash Doc_ID -> Shard map (Req 5.7)
     * @param failureRecorder invoked with the cause when an operation reverts on failure; may be null
     */
    public Indexer(DocIdAllocator docIdAllocator,
                   ConsistentHashShardMap shardMap,
                   Consumer<String> failureRecorder) {
        this.docIdAllocator = Objects.requireNonNull(docIdAllocator, "docIdAllocator must not be null");
        this.shardMap = Objects.requireNonNull(shardMap, "shardMap must not be null");
        this.failureRecorder = failureRecorder;
    }

    /**
     * Indexes a parsed document for the first time: assigns a unique Doc_ID (Req 5.3) and adds that
     * Doc_ID with its ascending positions to the posting list of every term the document contains
     * (Req 5.1, 5.2). The document is routed to exactly one shard (Req 5.7).
     *
     * @param doc the parsed document to index
     * @return the assigned Doc_ID via {@link IndexResult#docId()} on success, or a reverted result
     *         on failure (Req 5.6)
     */
    public IndexResult index(ParsedDocument doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        DocId docId = docIdAllocator.allocate();
        Segment segment = SegmentBuilder.buildSegment(doc);
        return applySegment(docId, segment, "index");
    }

    /**
     * Re-indexes a document after its content changed: builds a new per-Doc_ID {@link Segment} and
     * atomically replaces the prior postings for that Doc_ID with the new ones under a per-shard
     * lock, so no query observes a mixture of prior and current postings (Req 5.4). On failure the
     * shard reverts to its prior state and the failure is recorded (Req 5.6).
     *
     * @param doc the parsed document (its {@link ParsedDocument#docId()} must be set)
     * @return a success result, or a reverted result on failure (Req 5.6)
     * @throws IllegalArgumentException if the document has no Doc_ID assigned
     */
    public IndexResult reindex(ParsedDocument doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        DocId docId = doc.docId();
        if (docId == null) {
            throw new IllegalArgumentException("reindex requires a document with an assigned Doc_ID");
        }
        Segment segment = SegmentBuilder.buildSegment(doc);
        return applySegment(docId, segment, "reindex");
    }

    /**
     * Removes every posting referencing {@code docId} (Req 5.5), so the Doc_ID is no longer a
     * candidate for any query. Used both for explicit deletion and for dropping documents that
     * return HTTP 404/410 on re-fetch (Req 6.2). The pointer swap is atomic under the shard lock.
     *
     * @param docId the Doc_ID to remove
     * @return a success result if the Doc_ID was present and removed, a not-found result if it was
     *         absent, or a reverted result on failure (Req 5.6)
     */
    public IndexResult remove(DocId docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        ShardIndex shard = shardOf(docId);
        synchronized (shard.lock) {
            Map<String, PostingList> current = shard.snapshot();
            if (!referencesDocId(current, docId)) {
                return IndexResult.notFound(docId);
            }
            try {
                Map<String, PostingList> next = cloneWithout(current, docId);
                shard.pointerTable.set(next); // single atomic pointer swap (Req 5.4)
                return IndexResult.success(docId);
            } catch (RuntimeException e) {
                // Pointer never swapped: shard still reflects its prior state (Req 5.6).
                return recordRevert(docId, "remove", e);
            }
        }
    }

    /**
     * Maps a Doc_ID to exactly one Shard via the control-plane consistent-hash shard map
     * (Req 5.7). Deterministic pure function of the Doc_ID and the current shard set.
     */
    public ShardId shardFor(DocId docId) {
        return shardMap.shardFor(Objects.requireNonNull(docId, "docId must not be null"));
    }

    /**
     * Returns the postings for {@code term} on the shard that owns {@code docId}'s space, in
     * ascending Doc_ID order. Exposed for retrieval/testing; reads a single atomic snapshot so the
     * result never reflects a partially-applied mutation (Req 5.4).
     */
    public List<Posting> postings(ShardId shardId, String term) {
        Objects.requireNonNull(shardId, "shardId must not be null");
        Objects.requireNonNull(term, "term must not be null");
        ShardIndex shard = shards.get(shardId);
        if (shard == null) {
            return List.of();
        }
        PostingList list = shard.snapshot().get(term);
        return list == null ? List.of() : list.postings();
    }

    /** Returns {@code true} iff any posting on {@code docId}'s shard references that Doc_ID. */
    public boolean contains(DocId docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        ShardIndex shard = shards.get(shardFor(docId));
        return shard != null && referencesDocId(shard.snapshot(), docId);
    }

    // --- internals -------------------------------------------------------------------------

    /**
     * Applies a freshly-built segment for {@code docId}: replaces any prior postings for the Doc_ID
     * with those in {@code segment}, atomically, under the owning shard's lock (Req 5.4). Shared by
     * {@link #index} and {@link #reindex}. On failure reverts and records (Req 5.6).
     */
    private IndexResult applySegment(DocId docId, Segment segment, String op) {
        ShardIndex shard = shardOf(docId);
        synchronized (shard.lock) {
            Map<String, PostingList> current = shard.snapshot();
            try {
                // Build the new snapshot off a deep copy so the published snapshot is untouched
                // until the single pointer swap below (no mixed state observable — Req 5.4).
                Map<String, PostingList> next = cloneWithout(current, docId);
                for (String term : segment.terms()) {
                    List<Integer> positions = segment.positions(term);
                    PostingList list = next.computeIfAbsent(term, t -> new PostingList());
                    list.addPosting(docId, positions); // ascending positions preserved (Req 5.1, 5.2)
                }
                shard.pointerTable.set(immutable(next)); // single atomic pointer swap (Req 5.4)
                return IndexResult.success(docId);
            } catch (RuntimeException e) {
                // Nothing was published: the shard still holds `current` (Req 5.6).
                return recordRevert(docId, op, e);
            }
        }
    }

    /** The {@link ShardIndex} that owns {@code docId}, created on first use. */
    private ShardIndex shardOf(DocId docId) {
        ShardId shardId = shardFor(docId);
        return shards.computeIfAbsent(shardId, s -> new ShardIndex());
    }

    /**
     * Deep-copies {@code source} into a fresh mutable snapshot with all postings for {@code docId}
     * removed. Copying every {@link PostingList} guarantees the currently published snapshot is
     * never mutated, which is what keeps the swap atomic for concurrent readers (Req 5.4).
     */
    private static Map<String, PostingList> cloneWithout(Map<String, PostingList> source, DocId docId) {
        Map<String, PostingList> copy = new TreeMap<>();
        for (var entry : source.entrySet()) {
            PostingList original = entry.getValue();
            PostingList cloned = new PostingList();
            for (Posting posting : original.postings()) {
                if (!posting.docId().equals(docId)) {
                    cloned.addPosting(posting.docId(), posting.positions());
                }
            }
            if (!cloned.isEmpty()) {
                copy.put(entry.getKey(), cloned);
            }
        }
        return copy;
    }

    /** Wraps a mutable snapshot as an unmodifiable map for safe publication. */
    private static Map<String, PostingList> immutable(Map<String, PostingList> snapshot) {
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    /** True iff any posting list in {@code snapshot} references {@code docId}. */
    private static boolean referencesDocId(Map<String, PostingList> snapshot, DocId docId) {
        for (PostingList list : snapshot.values()) {
            if (list.contains(docId)) {
                return true;
            }
        }
        return false;
    }

    /** Records an indexing failure (Req 5.6) and returns the reverted result. */
    private IndexResult recordRevert(DocId docId, String op, RuntimeException cause) {
        String message = "Indexer." + op + " failed for " + docId + ": " + cause.getMessage();
        if (failureRecorder != null) {
            try {
                failureRecorder.accept(message);
            } catch (RuntimeException ignored) {
                // Recording must never mask the revert (aligns with Req 15.6 non-aborting recording).
            }
        }
        return IndexResult.revertedOnFailure(docId, message);
    }
}
