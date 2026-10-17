package com.searchengine.index;

import com.searchengine.common.DocId;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DocIdAllocator: the monotonic Doc_ID allocator (design §4 "assigns Doc_IDs from a monotonic
 * allocator (uniqueness, Req 5.3)"; Data Structures table "Monotonic Doc_ID allocator").
 *
 * <p>When a Document is indexed for the first time the Indexer requests a Doc_ID from this
 * allocator. Each call to {@link #allocate()} returns a strictly greater value than every
 * previous call, so allocated Doc_IDs are:
 *
 * <ul>
 *   <li><b>unique</b> across all Documents in the Inverted_Index (Req 5.3), and</li>
 *   <li><b>monotonically increasing</b>, which also keeps posting lists (which store ascending
 *       Doc_IDs, Req 5.1) cheap to maintain for freshly indexed documents.</li>
 * </ul>
 *
 * <p>The allocator is safe for concurrent use: the Indexer fleet may allocate Doc_IDs from
 * multiple threads, and the underlying {@link AtomicLong} guarantees that no two callers ever
 * observe the same value.
 */
public final class DocIdAllocator {

    /**
     * Holds the value that was most recently allocated. It starts one below the first value so
     * that the first {@link #allocate()} returns {@code firstId}.
     */
    private final AtomicLong lastAllocated;

    /** Creates an allocator whose first allocated Doc_ID is {@code 0}. */
    public DocIdAllocator() {
        this(0L);
    }

    /**
     * Creates an allocator whose first allocated Doc_ID is {@code firstId}. Useful when a shard
     * or index must resume allocation above a previously persisted high-water mark.
     *
     * @param firstId the first (non-negative) value {@link #allocate()} will return
     * @throws IllegalArgumentException if {@code firstId} is negative
     */
    public DocIdAllocator(long firstId) {
        if (firstId < 0) {
            throw new IllegalArgumentException("firstId must be non-negative, got: " + firstId);
        }
        this.lastAllocated = new AtomicLong(firstId - 1);
    }

    /**
     * Allocates the next unique, monotonically increasing Doc_ID.
     *
     * @return a Doc_ID strictly greater than every previously allocated Doc_ID (Req 5.3)
     * @throws IllegalStateException if the allocator has exhausted the {@code long} Doc_ID space
     */
    public DocId allocate() {
        long next = lastAllocated.incrementAndGet();
        if (next < 0) {
            // Overflow of the long space; roll back so the state stays consistent.
            lastAllocated.decrementAndGet();
            throw new IllegalStateException("DocId space exhausted");
        }
        return DocId.of(next);
    }

    /**
     * Returns the most recently allocated Doc_ID value, or {@code firstId - 1} if none has been
     * allocated yet. Exposed for persistence / high-water-mark checkpointing.
     */
    public long lastAllocatedValue() {
        return lastAllocated.get();
    }
}
