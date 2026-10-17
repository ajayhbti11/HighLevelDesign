package com.searchengine.serving;

import java.time.Duration;
import java.util.Objects;

/**
 * ServingConfig: the tunable parameters of the {@link ServingLayer} (design "Latency Budget";
 * Req 11.3, 12.3, 12.4).
 *
 * @param perShardDeadline the per-shard hard deadline for candidate retrieval (design: 200 ms;
 *                         Req 12.4)
 * @param pageSize         the configured page size, bounded to {@code [1, 100]} (Req 11.3)
 */
public record ServingConfig(Duration perShardDeadline, int pageSize) {

    /** Default per-shard deadline from the design's Latency Budget (Req 12.4). */
    public static final Duration DEFAULT_PER_SHARD_DEADLINE = Duration.ofMillis(200);

    public ServingConfig {
        Objects.requireNonNull(perShardDeadline, "perShardDeadline must not be null");
        if (perShardDeadline.isZero() || perShardDeadline.isNegative()) {
            throw new IllegalArgumentException("perShardDeadline must be positive, got: " + perShardDeadline);
        }
        if (pageSize < Paginator.MIN_PAGE_SIZE || pageSize > Paginator.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be within [" + Paginator.MIN_PAGE_SIZE + ", "
                            + Paginator.MAX_PAGE_SIZE + "], got: " + pageSize);
        }
    }

    /** A config with the design-default 200 ms per-shard deadline and the given page size. */
    public static ServingConfig withPageSize(int pageSize) {
        return new ServingConfig(DEFAULT_PER_SHARD_DEADLINE, Paginator.resolvePageSize(pageSize));
    }

    /** A config with the design-default deadline and default page size (Req 11.3). */
    public static ServingConfig defaults() {
        return new ServingConfig(DEFAULT_PER_SHARD_DEADLINE, Paginator.DEFAULT_PAGE_SIZE);
    }
}
