package com.searchengine.controlplane;

import com.searchengine.common.DocId;
import com.searchengine.common.ShardId;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * ConsistentHashShardMap: the Doc_ID -> Shard mapping for the Inverted_Index (design §4 and the
 * "Consistent-hash shard map" row of the Key Data Structures table; "Sharding, Partitioning, and
 * Replication Strategy").
 *
 * <p>The design specifies the mapping as {@code shardFor(docId) = hash(docId) mod shardCount},
 * with consistent hashing chosen so that adding shards on scale-out remaps only a minimal subset
 * of Doc_IDs (Req 14.1, 14.3) rather than reshuffling every Doc_ID. This class provides both
 * guarantees:
 *
 * <ul>
 *   <li><b>Deterministic, total, pure function (Req 5.7):</b> {@link #shardFor(DocId)} depends
 *       only on the Doc_ID and the current shard set. For a fixed shard set the same Doc_ID always
 *       maps to exactly one shard, so each Doc_ID lives in exactly one shard's primary set.</li>
 *   <li><b>Minimal remap on scale-out (Req 14.1, 14.3):</b> shards are placed on a hash ring with
 *       virtual nodes; adding a shard only moves the Doc_IDs that fall on the arcs newly owned by
 *       the added shard, leaving the rest untouched.</li>
 * </ul>
 *
 * <p>The map is immutable: {@link #withAddedShard(ShardId)} returns a new map, keeping the
 * mapping snapshot stable for readers (the serving plane) while the control plane evolves the
 * shard set.
 */
public final class ConsistentHashShardMap {

    /** Number of points each physical shard is placed at on the ring, for even distribution. */
    private static final int DEFAULT_VIRTUAL_NODES = 128;

    private final int virtualNodes;

    /** ring position (unsigned-ordered as long) -> owning ShardId. */
    private final NavigableMap<Long, ShardId> ring;

    /** The distinct shards currently in the map, kept for equality/inspection. */
    private final List<ShardId> shards;

    private ConsistentHashShardMap(int virtualNodes, List<ShardId> shards) {
        this.virtualNodes = virtualNodes;
        this.shards = List.copyOf(shards);
        this.ring = buildRing(this.shards, virtualNodes);
    }

    /**
     * Creates a shard map over {@code shardCount} shards with ids {@code 0..shardCount-1}.
     *
     * @param shardCount the number of shards (must be positive)
     */
    public static ConsistentHashShardMap ofShardCount(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive, got: " + shardCount);
        }
        List<ShardId> ids = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            ids.add(ShardId.of(i));
        }
        return new ConsistentHashShardMap(DEFAULT_VIRTUAL_NODES, ids);
    }

    /**
     * Creates a shard map over the given distinct shards.
     *
     * @param shards the non-empty set of shards (duplicates rejected)
     */
    public static ConsistentHashShardMap of(List<ShardId> shards) {
        Objects.requireNonNull(shards, "shards must not be null");
        if (shards.isEmpty()) {
            throw new IllegalArgumentException("shards must not be empty");
        }
        List<ShardId> distinct = new ArrayList<>();
        for (ShardId s : shards) {
            Objects.requireNonNull(s, "shard must not be null");
            if (distinct.contains(s)) {
                throw new IllegalArgumentException("duplicate shard: " + s);
            }
            distinct.add(s);
        }
        return new ConsistentHashShardMap(DEFAULT_VIRTUAL_NODES, distinct);
    }

    /**
     * Maps a Doc_ID to exactly one Shard (Req 5.7). Deterministic pure function of the Doc_ID and
     * the current shard set: walks clockwise on the ring to the first virtual node at or after the
     * Doc_ID's hash, wrapping around to the first node when past the end.
     *
     * @param docId the Doc_ID to place
     * @return the owning ShardId (never {@code null})
     */
    public ShardId shardFor(DocId docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        long h = hashDocId(docId.value());
        // First entry whose ring position >= h, wrapping to the first entry (the ring is circular).
        var entry = ring.ceilingEntry(h);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    /**
     * Returns a new shard map with {@code newShard} added (scale-out). Only Doc_IDs on the arcs
     * newly owned by {@code newShard} change owner; all others keep their prior shard, so
     * reassignment moves a minimal subset with no loss or duplication (Req 14.1, 14.3).
     *
     * @param newShard the shard to add (must not already be present)
     */
    public ConsistentHashShardMap withAddedShard(ShardId newShard) {
        Objects.requireNonNull(newShard, "newShard must not be null");
        if (shards.contains(newShard)) {
            throw new IllegalArgumentException("shard already present: " + newShard);
        }
        List<ShardId> next = new ArrayList<>(shards);
        next.add(newShard);
        return new ConsistentHashShardMap(virtualNodes, next);
    }

    /** The number of shards currently in the map. */
    public int shardCount() {
        return shards.size();
    }

    /** Immutable view of the shards currently in the map, in insertion order. */
    public List<ShardId> shards() {
        return shards;
    }

    private static NavigableMap<Long, ShardId> buildRing(List<ShardId> shards, int virtualNodes) {
        TreeMap<Long, ShardId> ring = new TreeMap<>();
        for (ShardId shard : shards) {
            for (int v = 0; v < virtualNodes; v++) {
                long point = hashRingPoint(shard.value(), v);
                // Resolve the extremely rare collision deterministically by probing forward so the
                // ring stays a total function; the lower (shard, vnode) keeps the slot.
                while (ring.containsKey(point)) {
                    point++;
                }
                ring.put(point, shard);
            }
        }
        return ring;
    }

    /**
     * Hash of a virtual-node point on the ring. Mixes the shard id and the virtual-node index with
     * a stable finalizer so points are spread evenly and deterministically.
     */
    private static long hashRingPoint(int shardValue, int vnode) {
        return mix64((((long) shardValue) << 32) ^ (0x9E3779B97F4A7C15L * (vnode + 1)));
    }

    /** Hash of a Doc_ID onto the ring's key space. Deterministic and pure. */
    private static long hashDocId(long docIdValue) {
        return mix64(docIdValue);
    }

    /** SplitMix64-style finalizer: a strong, deterministic 64-bit mixing function. */
    private static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public String toString() {
        return "ConsistentHashShardMap(shards=" + shards + ", virtualNodes=" + virtualNodes + ")";
    }
}
