package com.searchengine.controlplane;

import com.searchengine.common.ShardId;

import java.util.List;
import java.util.Objects;

/**
 * Result of the coverage check across all shards (design §E; Req 13.3, 13.4).
 *
 * <p>Coverage is <b>complete</b> when every shard has at least one healthy replica, so a full
 * Search_Results_Page can be produced (Req 13.3). Coverage is <b>partial</b> when any shard has
 * zero healthy replicas; the serving plane must then degrade to a partial page with an
 * incomplete-shard-coverage status and the count/identity of the uncovered shards (Req 13.4).
 */
public final class CoverageStatus {

    private final boolean complete;
    private final List<ShardId> uncoveredShards;

    private CoverageStatus(boolean complete, List<ShardId> uncoveredShards) {
        this.complete = complete;
        this.uncoveredShards = List.copyOf(uncoveredShards);
    }

    /** Complete coverage: every shard has at least one healthy replica (Req 13.3). */
    public static CoverageStatus complete() {
        return new CoverageStatus(true, List.of());
    }

    /** Partial coverage: the given shards have zero healthy replicas (Req 13.4). */
    public static CoverageStatus partial(List<ShardId> uncoveredShards) {
        Objects.requireNonNull(uncoveredShards, "uncoveredShards must not be null");
        if (uncoveredShards.isEmpty()) {
            throw new IllegalArgumentException("partial coverage requires at least one uncovered shard");
        }
        return new CoverageStatus(false, uncoveredShards);
    }

    /** True iff every shard has at least one healthy replica (Req 13.3). */
    public boolean isComplete() {
        return complete;
    }

    /** The shards with zero healthy replicas; empty when coverage is complete (Req 13.4). */
    public List<ShardId> uncoveredShards() {
        return uncoveredShards;
    }

    /** The number of shards with zero healthy replicas (Req 13.4). */
    public int uncoveredShardCount() {
        return uncoveredShards.size();
    }

    @Override
    public String toString() {
        return complete
                ? "CoverageStatus(complete)"
                : "CoverageStatus(partial, uncovered=" + uncoveredShards + ")";
    }
}
