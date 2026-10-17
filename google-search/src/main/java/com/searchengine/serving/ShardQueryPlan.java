package com.searchengine.serving;

import com.searchengine.common.ShardId;
import com.searchengine.retrieval.ShardPostingSource;

import java.time.Duration;

/**
 * ShardQueryPlan: resolves, for a single shard, the {@link ShardPostingSource} the Serving_Layer
 * should query against within the per-shard deadline (design "Latency Budget" — per-shard 200 ms
 * hard deadline; §E replica failover; Req 12.4, 13.4).
 *
 * <p>The Serving_Layer consults the control plane to pick a healthy replica for each shard, then
 * asks the plan to open a posting source for that replica under a deadline. The outcome is one of
 * three cases the serving layer must treat differently:
 * <ul>
 *   <li><b>available</b> — a replica responded within the deadline; its postings contribute to the
 *       candidate set.</li>
 *   <li><b>no healthy replica</b> — every replica of the shard is down, so the shard cannot be
 *       covered at all; this degrades coverage to <em>incomplete-shard-coverage</em> (Req 13.4).</li>
 *   <li><b>timed out</b> — a replica was selected but did not respond within the per-shard deadline;
 *       responders still produce a page and the shard is counted as non-responding, yielding a
 *       <em>partial</em> result (Req 12.4).</li>
 * </ul>
 *
 * <p>Modeling shard access behind this interface keeps the Serving_Layer's orchestration testable
 * without a live cluster: tests provide a plan that returns fixed outcomes, while production wiring
 * backs it with a real replica RPC bounded by the deadline.
 */
@FunctionalInterface
public interface ShardQueryPlan {

    /**
     * Opens a posting source for {@code shardId} within {@code deadline}.
     *
     * @param shardId  the shard to query
     * @param deadline the per-shard hard deadline (design: 200 ms; Req 12.4)
     * @return the outcome: available (with a source), no-replica, or timed-out
     */
    Outcome openSource(ShardId shardId, Duration deadline);

    /** The kind of shard-access outcome. */
    enum Kind {
        /** A replica responded within the deadline. */
        AVAILABLE,
        /** No healthy replica exists for the shard (Req 13.4 — incomplete shard coverage). */
        NO_HEALTHY_REPLICA,
        /** A replica was selected but did not respond within the deadline (Req 12.4 — partial). */
        TIMED_OUT
    }

    /**
     * The result of attempting to open a shard's posting source under a deadline.
     *
     * @param kind   which of the three outcomes occurred
     * @param source the posting source when {@link Kind#AVAILABLE}; {@code null} otherwise
     */
    record Outcome(Kind kind, ShardPostingSource source) {

        public Outcome {
            java.util.Objects.requireNonNull(kind, "kind must not be null");
            if (kind == Kind.AVAILABLE && source == null) {
                throw new IllegalArgumentException("AVAILABLE outcome requires a non-null source");
            }
            if (kind != Kind.AVAILABLE && source != null) {
                throw new IllegalArgumentException(kind + " outcome must not carry a source");
            }
        }

        /** An available shard whose replica responded within the deadline. */
        public static Outcome available(ShardPostingSource source) {
            return new Outcome(Kind.AVAILABLE, source);
        }

        /** A shard with no healthy replica (Req 13.4). */
        public static Outcome noHealthyReplica() {
            return new Outcome(Kind.NO_HEALTHY_REPLICA, null);
        }

        /** A shard whose selected replica did not respond within the deadline (Req 12.4). */
        public static Outcome timedOut() {
            return new Outcome(Kind.TIMED_OUT, null);
        }
    }
}
