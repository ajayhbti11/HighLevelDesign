package com.searchengine.controlplane;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of {@link ShardReplicationControlPlane#pickHealthyReplica} for one shard (design §E
 * {@code pickHealthyReplica(shard)}; Req 13.2, 13.4).
 *
 * <ul>
 *   <li>A <b>present</b> selection carries the healthy {@link ReplicaId} the retrieval should route
 *       to whenever the shard has at least one healthy replica (Req 13.2).</li>
 *   <li>An <b>empty</b> selection signals that no replica of the shard is available, so the shard
 *       must be skipped and coverage degrades to partial (Req 13.4).</li>
 * </ul>
 */
public final class ReplicaSelection {

    private final ReplicaId replica; // null iff no healthy replica

    private ReplicaSelection(ReplicaId replica) {
        this.replica = replica;
    }

    /** A selection that routes to the given healthy replica (Req 13.2). */
    public static ReplicaSelection of(ReplicaId replica) {
        return new ReplicaSelection(Objects.requireNonNull(replica, "replica must not be null"));
    }

    /** A selection signalling that no healthy replica is available for the shard (Req 13.4). */
    public static ReplicaSelection none() {
        return new ReplicaSelection(null);
    }

    /** True when a healthy replica was selected. */
    public boolean isPresent() {
        return replica != null;
    }

    /** The selected healthy replica, or empty when none is available. */
    public Optional<ReplicaId> replica() {
        return Optional.ofNullable(replica);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReplicaSelection)) {
            return false;
        }
        return Objects.equals(replica, ((ReplicaSelection) o).replica);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(replica);
    }

    @Override
    public String toString() {
        return replica == null ? "ReplicaSelection(none)" : "ReplicaSelection(" + replica + ")";
    }
}
