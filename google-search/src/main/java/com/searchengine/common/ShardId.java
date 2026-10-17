package com.searchengine.common;

import java.util.Objects;

/**
 * ShardId: identifies a horizontal partition of the Inverted_Index.
 *
 * <p>Each Doc_ID is assigned to exactly one Shard (Req 5.7). The shard map maps
 * {@code shardFor(docId) = hash(docId) mod shardCount} onto a ShardId.
 */
public final class ShardId implements Comparable<ShardId> {

    private final int value;

    private ShardId(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("ShardId must be non-negative, got: " + value);
        }
        this.value = value;
    }

    public static ShardId of(int value) {
        return new ShardId(value);
    }

    public int value() {
        return value;
    }

    @Override
    public int compareTo(ShardId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShardId)) {
            return false;
        }
        return value == ((ShardId) o).value;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return "ShardId(" + value + ")";
    }
}
