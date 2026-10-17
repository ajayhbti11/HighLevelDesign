package com.searchengine.controlplane;

import com.searchengine.common.DocId;
import com.searchengine.common.ShardId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a shard scale-out / rebalancing attempt (design "Rebalancing"; Req 14.1, 14.3, 14.4).
 *
 * <p>A scale-out is either:
 * <ul>
 *   <li><b>Scaled:</b> one or more shards were added and the Doc_ID assignment was recomputed via
 *       consistent hashing so that every shard holds at or below the configured maximum
 *       (Req 14.1) and each Doc_ID stays assigned to exactly one shard with no loss or duplication
 *       (Req 14.3). Carries the new {@link ConsistentHashShardMap} and the resulting assignment.</li>
 *   <li><b>Capacity-exhausted:</b> no additional shard capacity could be provisioned, so a
 *       capacity-exhaustion condition is recorded and the prior assignment is retained (Req 14.4).</li>
 * </ul>
 */
public final class ScaleOutResult {

    private final boolean scaled;
    private final ConsistentHashShardMap shardMap;
    private final Map<DocId, ShardId> assignment;
    private final List<ShardId> addedShards;

    private ScaleOutResult(
            boolean scaled,
            ConsistentHashShardMap shardMap,
            Map<DocId, ShardId> assignment,
            List<ShardId> addedShards) {
        this.scaled = scaled;
        this.shardMap = shardMap;
        this.assignment = Map.copyOf(assignment);
        this.addedShards = List.copyOf(addedShards);
    }

    /** A successful scale-out with the new shard map, assignment, and the shards that were added. */
    static ScaleOutResult scaled(
            ConsistentHashShardMap shardMap,
            Map<DocId, ShardId> assignment,
            List<ShardId> addedShards) {
        Objects.requireNonNull(shardMap, "shardMap must not be null");
        Objects.requireNonNull(assignment, "assignment must not be null");
        Objects.requireNonNull(addedShards, "addedShards must not be null");
        return new ScaleOutResult(true, shardMap, assignment, addedShards);
    }

    /** A capacity-exhausted outcome retaining the prior shard map and assignment (Req 14.4). */
    static ScaleOutResult capacityExhausted(
            ConsistentHashShardMap shardMap, Map<DocId, ShardId> assignment) {
        Objects.requireNonNull(shardMap, "shardMap must not be null");
        Objects.requireNonNull(assignment, "assignment must not be null");
        return new ScaleOutResult(false, shardMap, assignment, List.of());
    }

    /** True when shards were added and the assignment was rebalanced (Req 14.1, 14.3). */
    public boolean isScaled() {
        return scaled;
    }

    /** True when no additional capacity could be provisioned (Req 14.4). */
    public boolean isCapacityExhausted() {
        return !scaled;
    }

    /** The shard map after the scale-out (unchanged when capacity was exhausted). */
    public ConsistentHashShardMap shardMap() {
        return shardMap;
    }

    /** The Doc_ID -> Shard assignment after the scale-out; each Doc_ID appears exactly once. */
    public Map<DocId, ShardId> assignment() {
        return assignment;
    }

    /** The shards added during this scale-out; empty when capacity was exhausted. */
    public List<ShardId> addedShards() {
        return addedShards;
    }

    @Override
    public String toString() {
        return scaled
                ? "ScaleOutResult(scaled, added=" + addedShards + ", shards=" + shardMap.shardCount() + ")"
                : "ScaleOutResult(capacityExhausted)";
    }
}
