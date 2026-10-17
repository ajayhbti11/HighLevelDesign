package com.searchengine.controlplane;

import com.searchengine.common.ShardId;

import java.util.Objects;

/**
 * ReplicaId: identifies one of the {@code replicationFactor >= 2} independent replicas that store
 * a single {@link ShardId} (design "Sharding, Partitioning, and Replication Strategy"; Req 13.1).
 *
 * <p>A replica is identified by its owning shard plus a zero-based replica index within that shard.
 * Reads target one healthy replica per shard, failing over to another replica of the same shard
 * when a replica is unhealthy (Req 13.2).
 */
public final class ReplicaId implements Comparable<ReplicaId> {

    private final ShardId shardId;
    private final int index;

    private ReplicaId(ShardId shardId, int index) {
        this.shardId = Objects.requireNonNull(shardId, "shardId must not be null");
        if (index < 0) {
            throw new IllegalArgumentException("replica index must be non-negative, got: " + index);
        }
        this.index = index;
    }

    public static ReplicaId of(ShardId shardId, int index) {
        return new ReplicaId(shardId, index);
    }

    public ShardId shardId() {
        return shardId;
    }

    public int index() {
        return index;
    }

    @Override
    public int compareTo(ReplicaId other) {
        int byShard = this.shardId.compareTo(other.shardId);
        if (byShard != 0) {
            return byShard;
        }
        return Integer.compare(this.index, other.index);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReplicaId)) {
            return false;
        }
        ReplicaId that = (ReplicaId) o;
        return index == that.index && shardId.equals(that.shardId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardId, index);
    }

    @Override
    public String toString() {
        return "ReplicaId(" + shardId + ", index=" + index + ")";
    }
}
