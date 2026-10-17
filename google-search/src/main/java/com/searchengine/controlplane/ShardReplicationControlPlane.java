package com.searchengine.controlplane;

import com.searchengine.common.DocId;
import com.searchengine.common.ShardId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ShardReplicationControlPlane: the control-plane logic for replica selection, coverage checking,
 * and shard scale-out / rebalancing (design §E "Candidate Retrieval + Shard Merge" replica pick +
 * failover; "Sharding, Partitioning, and Replication Strategy"; "Deployment / Scaling View").
 *
 * <p>Responsibilities, mapped to the requirements:
 * <ul>
 *   <li><b>Replica selection (Req 13.2 / 13.4):</b> each shard tracks a health-state per replica.
 *       {@link #pickHealthyReplica(ShardId)} returns a healthy replica whenever the shard has at
 *       least one healthy replica, and signals "none available" otherwise.</li>
 *   <li><b>Coverage (Req 13.3 / 13.4):</b> {@link #coverage()} reports complete when every shard has
 *       at least one healthy replica, and partial (with the uncovered shards) when any shard has
 *       zero healthy replicas.</li>
 *   <li><b>Scale-out / rebalancing (Req 14.1 / 14.3 / 14.4):</b> {@link #scaleOutForAssignment} adds
 *       shards when docs-per-shard exceeds the configured maximum and recomputes the assignment via
 *       {@link ConsistentHashShardMap} so each Doc_ID stays in exactly one shard with no loss or
 *       duplication; if no additional capacity can be provisioned it records a capacity-exhaustion
 *       condition instead.</li>
 * </ul>
 *
 * <p>Replica selection is deterministic: among a shard's healthy replicas the lowest replica index
 * is chosen, so identical health states always route identically.
 *
 * <p>This class is mutable with respect to replica health (the control plane updates health as
 * health checks report in) but treats scale-out as a pure computation that returns a
 * {@link ScaleOutResult}; callers decide whether to adopt the new shard map.
 */
public final class ShardReplicationControlPlane {

    /** Per-shard replica health: shardId -> (replica index -> healthy?), ordered for determinism. */
    private final TreeMap<ShardId, TreeMap<Integer, Boolean>> health = new TreeMap<>();

    /** The configured maximum number of Documents a single shard may hold (Req 14.1). */
    private final int maxDocsPerShard;

    /** The maximum number of shards that can ever be provisioned; beyond this, capacity is exhausted (Req 14.4). */
    private final int maxShardCount;

    /** The replication factor applied to shards added during scale-out (Req 13.1). */
    private final int replicationFactor;

    private ShardReplicationControlPlane(int maxDocsPerShard, int maxShardCount, int replicationFactor) {
        if (maxDocsPerShard <= 0) {
            throw new IllegalArgumentException("maxDocsPerShard must be positive, got: " + maxDocsPerShard);
        }
        if (replicationFactor < 2) {
            throw new IllegalArgumentException("replicationFactor must be >= 2 (Req 13.1), got: " + replicationFactor);
        }
        if (maxShardCount < 1) {
            throw new IllegalArgumentException("maxShardCount must be >= 1, got: " + maxShardCount);
        }
        this.maxDocsPerShard = maxDocsPerShard;
        this.maxShardCount = maxShardCount;
        this.replicationFactor = replicationFactor;
    }

    /**
     * Creates a control plane.
     *
     * @param maxDocsPerShard   maximum Documents per shard before scale-out is required (Req 14.1)
     * @param maxShardCount     hard cap on provisionable shards; beyond it, capacity is exhausted (Req 14.4)
     * @param replicationFactor replicas per shard for shards added during scale-out (Req 13.1, >= 2)
     */
    public static ShardReplicationControlPlane create(
            int maxDocsPerShard, int maxShardCount, int replicationFactor) {
        return new ShardReplicationControlPlane(maxDocsPerShard, maxShardCount, replicationFactor);
    }

    // ------------------------------------------------------------------------
    // Replica health tracking (Req 13.1, 13.2)
    // ------------------------------------------------------------------------

    /**
     * Registers a shard with {@code replicaCount} replicas, all initially healthy (Req 13.1).
     * Re-registering a shard replaces its prior replica set.
     *
     * @param shardId      the shard
     * @param replicaCount the number of replicas (must be >= 2 per Req 13.1)
     */
    public void registerShard(ShardId shardId, int replicaCount) {
        Objects.requireNonNull(shardId, "shardId must not be null");
        if (replicaCount < 2) {
            throw new IllegalArgumentException("replicaCount must be >= 2 (Req 13.1), got: " + replicaCount);
        }
        TreeMap<Integer, Boolean> replicas = new TreeMap<>();
        for (int i = 0; i < replicaCount; i++) {
            replicas.put(i, Boolean.TRUE);
        }
        health.put(shardId, replicas);
    }

    /**
     * Marks a replica healthy or unhealthy following a health check (Req 13.2). Marking a replica
     * unhealthy makes {@link #pickHealthyReplica} fail over to another healthy replica of the same
     * shard, if any remain.
     *
     * @param replica the replica whose health changed
     * @param healthy the new health state
     * @throws IllegalArgumentException if the replica's shard or index is unknown
     */
    public void setReplicaHealth(ReplicaId replica, boolean healthy) {
        Objects.requireNonNull(replica, "replica must not be null");
        TreeMap<Integer, Boolean> replicas = health.get(replica.shardId());
        if (replicas == null || !replicas.containsKey(replica.index())) {
            throw new IllegalArgumentException("unknown replica: " + replica);
        }
        replicas.put(replica.index(), healthy);
    }

    // ------------------------------------------------------------------------
    // Replica selection (Req 13.2, 13.4)
    // ------------------------------------------------------------------------

    /**
     * Picks a healthy replica for the shard (design §E {@code pickHealthyReplica(shard)}).
     *
     * <p>Returns a present {@link ReplicaSelection} carrying a healthy replica whenever the shard
     * has at least one healthy replica (Req 13.2); the lowest healthy replica index is chosen so
     * selection is deterministic. Returns {@link ReplicaSelection#none()} when the shard has zero
     * healthy replicas, signalling that retrieval must skip the shard and coverage degrades to
     * partial (Req 13.4).
     *
     * @param shardId the shard to route to
     * @return a healthy replica when one exists, else a "none available" signal
     * @throws IllegalArgumentException if the shard is not registered
     */
    public ReplicaSelection pickHealthyReplica(ShardId shardId) {
        Objects.requireNonNull(shardId, "shardId must not be null");
        TreeMap<Integer, Boolean> replicas = health.get(shardId);
        if (replicas == null) {
            throw new IllegalArgumentException("unknown shard: " + shardId);
        }
        for (Map.Entry<Integer, Boolean> e : replicas.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                return ReplicaSelection.of(ReplicaId.of(shardId, e.getKey()));
            }
        }
        return ReplicaSelection.none();
    }

    /**
     * Coverage check across all registered shards (design §E; Req 13.3, 13.4).
     *
     * @return {@link CoverageStatus#complete()} when every registered shard has at least one healthy
     *     replica (Req 13.3); otherwise {@link CoverageStatus#partial} listing every shard with zero
     *     healthy replicas (Req 13.4)
     */
    public CoverageStatus coverage() {
        List<ShardId> uncovered = new ArrayList<>();
        for (Map.Entry<ShardId, TreeMap<Integer, Boolean>> shard : health.entrySet()) {
            if (!hasHealthyReplica(shard.getValue())) {
                uncovered.add(shard.getKey());
            }
        }
        return uncovered.isEmpty() ? CoverageStatus.complete() : CoverageStatus.partial(uncovered);
    }

    private static boolean hasHealthyReplica(TreeMap<Integer, Boolean> replicas) {
        for (Boolean healthy : replicas.values()) {
            if (Boolean.TRUE.equals(healthy)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Scale-out / rebalancing (Req 14.1, 14.3, 14.4)
    // ------------------------------------------------------------------------

    /**
     * Scales out and rebalances a Doc_ID assignment when any shard exceeds the configured maximum
     * Documents per shard (design "Rebalancing"; Req 14.1, 14.3, 14.4).
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If no shard exceeds {@code maxDocsPerShard}, the assignment is already within bound and is
     *       recomputed from {@code shardMap} unchanged.</li>
     *   <li>Otherwise shards are added one at a time via {@link ConsistentHashShardMap#withAddedShard}
     *       and every Doc_ID is re-placed via {@link ConsistentHashShardMap#shardFor}, until every
     *       shard holds at or below the maximum (Req 14.1). Because the mapping is a total function of
     *       the Doc_ID, each Doc_ID lands in exactly one shard, so the Doc_ID set is conserved with no
     *       loss or duplication (Req 14.3).</li>
     *   <li>If the maximum shard count ({@code maxShardCount}) is reached before every shard is within
     *       bound, no further capacity can be provisioned: a capacity-exhaustion condition is recorded
     *       and the prior shard map / assignment is retained (Req 14.4).</li>
     * </ul>
     *
     * <p>Newly added shards are registered with {@code replicationFactor} healthy replicas (Req 13.1)
     * so they are immediately selectable by {@link #pickHealthyReplica}.
     *
     * @param shardMap the current Doc_ID -> Shard map (reused; Req 14.3)
     * @param docIds   the Doc_IDs currently in the index
     * @return a {@link ScaleOutResult} that is either scaled (Req 14.1/14.3) or capacity-exhausted (Req 14.4)
     */
    public ScaleOutResult scaleOutForAssignment(ConsistentHashShardMap shardMap, Set<DocId> docIds) {
        Objects.requireNonNull(shardMap, "shardMap must not be null");
        Objects.requireNonNull(docIds, "docIds must not be null");

        ConsistentHashShardMap current = shardMap;
        Map<DocId, ShardId> assignment = assign(current, docIds);
        List<ShardId> added = new ArrayList<>();

        while (exceedsMax(assignment)) {
            if (current.shardCount() >= maxShardCount) {
                // No additional shard capacity can be provisioned (Req 14.4). Record the condition
                // and retain the prior (best-effort) shard map and assignment.
                recordCapacityExhaustion(current.shardCount());
                return ScaleOutResult.capacityExhausted(current, assignment);
            }
            ShardId newShard = nextShardId(current);
            current = current.withAddedShard(newShard);
            registerShard(newShard, replicationFactor);
            added.add(newShard);
            assignment = assign(current, docIds);
        }

        return ScaleOutResult.scaled(current, assignment, added);
    }

    /** Recomputes the Doc_ID -> Shard assignment from the shard map (Req 14.3, single-shard membership). */
    private static Map<DocId, ShardId> assign(ConsistentHashShardMap shardMap, Set<DocId> docIds) {
        Map<DocId, ShardId> assignment = new LinkedHashMap<>();
        for (DocId docId : docIds) {
            assignment.put(docId, shardMap.shardFor(docId));
        }
        return assignment;
    }

    /** True iff some shard holds more than {@code maxDocsPerShard} Documents (Req 14.1). */
    private boolean exceedsMax(Map<DocId, ShardId> assignment) {
        Map<ShardId, Integer> counts = new TreeMap<>();
        for (ShardId shardId : assignment.values()) {
            counts.merge(shardId, 1, Integer::sum);
        }
        for (int count : counts.values()) {
            if (count > maxDocsPerShard) {
                return true;
            }
        }
        return false;
    }

    /** Allocates the next shard id as one past the current maximum shard value, keeping ids distinct. */
    private static ShardId nextShardId(ConsistentHashShardMap shardMap) {
        int max = -1;
        for (ShardId shardId : shardMap.shards()) {
            max = Math.max(max, shardId.value());
        }
        return ShardId.of(max + 1);
    }

    /**
     * Records a capacity-exhaustion condition (Req 14.4). The condition and the shard count at which
     * it occurred are retained so the serving/observability plane can surface it; the count of
     * recorded conditions is exposed via {@link #capacityExhaustionCount()}.
     */
    private void recordCapacityExhaustion(int shardCountAtExhaustion) {
        capacityExhaustionShardCounts.add(shardCountAtExhaustion);
    }

    /** Shard counts at which capacity exhaustion was recorded, in order (Req 14.4). */
    private final List<Integer> capacityExhaustionShardCounts = new ArrayList<>();

    /** The number of capacity-exhaustion conditions recorded so far (Req 14.4). */
    public int capacityExhaustionCount() {
        return capacityExhaustionShardCounts.size();
    }

    /** True iff at least one capacity-exhaustion condition has been recorded (Req 14.4). */
    public boolean capacityExhausted() {
        return !capacityExhaustionShardCounts.isEmpty();
    }

    // ------------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------------

    /** The registered shards, ordered by shard id. */
    public Set<ShardId> registeredShards() {
        return new TreeSet<>(health.keySet());
    }

    /** The configured maximum Documents per shard (Req 14.1). */
    public int maxDocsPerShard() {
        return maxDocsPerShard;
    }

    @Override
    public String toString() {
        return "ShardReplicationControlPlane(shards=" + health.keySet()
                + ", maxDocsPerShard=" + maxDocsPerShard
                + ", maxShardCount=" + maxShardCount
                + ", replicationFactor=" + replicationFactor + ")";
    }
}
