package com.searchengine.serving;

import com.searchengine.common.PageStatus;
import com.searchengine.common.RankedResult;
import com.searchengine.common.SearchResultsPage;
import com.searchengine.common.ShardId;
import com.searchengine.controlplane.ReplicaSelection;
import com.searchengine.controlplane.ShardReplicationControlPlane;
import com.searchengine.observability.Observability;
import com.searchengine.observability.QueryMetric;
import com.searchengine.query.QueryProcessor;
import com.searchengine.query.StructuredQuery;
import com.searchengine.ranking.RankingCandidate;
import com.searchengine.ranking.RankingResult;
import com.searchengine.ranking.Ranker;
import com.searchengine.retrieval.Candidate;
import com.searchengine.retrieval.CandidateRetrieval;
import com.searchengine.retrieval.RetrievalResult;
import com.searchengine.retrieval.ShardPostingSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ServingLayer: orchestrates the latency-critical serving plane end to end and applies admission
 * control (design §6 {@code interface ServingLayer}; data-flow "Serving plane"; Latency Budget;
 * Req 7.4, 8.4, 10.6, 11.5, 11.6, 12.3, 12.4, 13.4, 15.1).
 *
 * <p>{@link #search(String, int)} wires the stages of the design's serving data flow in order:
 * <ol>
 *   <li><b>Admission / rate-limit (Req 12.3).</b> {@link AdmissionController#tryAdmit} caps accepted
 *       queries at the configured peak rate; excess queries short-circuit to an empty page with
 *       {@link PageStatus#OVERLOADED}. No downstream work is done for a rejected query.</li>
 *   <li><b>Parse (Req 7.4).</b> {@link QueryProcessor#parse} produces a {@link StructuredQuery};
 *       an empty query (no terms after normalization) short-circuits to
 *       {@link PageStatus#NO_QUERY_TERMS}.</li>
 *   <li><b>Candidate retrieval (Req 8.x, 12.4, 13.4).</b> The layer fans out to every registered
 *       shard, picking a healthy replica per shard via the control plane and opening a posting
 *       source under the per-shard deadline through a {@link ShardQueryPlan}. Shards with no healthy
 *       replica degrade coverage (Req 13.4); shards that miss the deadline yield partial results
 *       (Req 12.4). Responders feed {@link CandidateRetrieval#retrieve}.</li>
 *   <li><b>Ranking (Req 10.6).</b> {@link Ranker#rank} orders the candidates; an empty candidate set
 *       ranks to an empty page without error.</li>
 *   <li><b>Pagination + assembly (Req 11.5, 11.6).</b> {@link Paginator#paginate} slices the ranked
 *       list and assembles {@link com.searchengine.common.ResultEntry ResultEntry}s, reporting
 *       {@code invalidPage} / {@code noFurtherResults} as needed.</li>
 *   <li><b>Metric emission (Req 15.1).</b> A {@link QueryMetric} (latency + result count) is recorded
 *       for every served query via {@link Observability}; recording never aborts the response.</li>
 * </ol>
 *
 * <p><b>Status mapping.</b> The final page status is chosen with this precedence:
 * {@code OVERLOADED} (Req 12.3) and {@code NO_QUERY_TERMS} (Req 7.4) are decided before retrieval.
 * After pagination, a shard-coverage condition <em>overrides</em> {@code OK}/{@code NO_MATCHES}:
 * any shard with no healthy replica yields {@link PageStatus#INCOMPLETE_SHARD_COVERAGE} (Req 13.4),
 * otherwise any timed-out shard yields {@link PageStatus#PARTIAL_RESULT} (Req 12.4), and the
 * non-responding shard count is carried as {@code partialShardCount}. {@code invalidPage}
 * (Req 11.6) and {@code noFurtherResults} (Req 11.5) from the paginator are preserved as-is, as is
 * {@code noMatches} (Req 8.4) when coverage is complete.
 *
 * <p>The layer is a pure orchestrator over injected collaborators; it holds no per-query mutable
 * state, so it is thread-safe provided its collaborators are.
 */
public final class ServingLayer {

    private final AdmissionController admissionController;
    private final QueryProcessor queryProcessor;
    private final ShardReplicationControlPlane controlPlane;
    private final ShardQueryPlan shardQueryPlan;
    private final Ranker ranker;
    private final RankingComponents rankingComponents;
    private final MetadataResolver metadataResolver;
    private final Observability observability;
    private final Observability.Clock clock;
    private final ServingConfig config;

    /**
     * Supplies the normalized ranking components (bm25 / authority) for a candidate Doc_ID
     * (design §G). Kept as a small injected strategy so the Serving_Layer does not embed scoring
     * data; production wiring resolves these from the index/authority stores.
     */
    @FunctionalInterface
    public interface RankingComponents {
        /** Returns the ranking candidate (normalized components) for the given retrieved candidate. */
        RankingCandidate forCandidate(Candidate candidate);
    }

    private ServingLayer(Builder b) {
        this.admissionController = Objects.requireNonNull(b.admissionController, "admissionController");
        this.queryProcessor = Objects.requireNonNull(b.queryProcessor, "queryProcessor");
        this.controlPlane = Objects.requireNonNull(b.controlPlane, "controlPlane");
        this.shardQueryPlan = Objects.requireNonNull(b.shardQueryPlan, "shardQueryPlan");
        this.ranker = Objects.requireNonNull(b.ranker, "ranker");
        this.rankingComponents = Objects.requireNonNull(b.rankingComponents, "rankingComponents");
        this.metadataResolver = Objects.requireNonNull(b.metadataResolver, "metadataResolver");
        this.observability = Objects.requireNonNull(b.observability, "observability");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.config = Objects.requireNonNull(b.config, "config");
    }

    /** A builder for the many collaborators the Serving_Layer wires together. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serves one query and returns its {@link SearchResultsPage} (design §6
     * {@code search(rawQuery, page, pageSize)}; Req 7.4, 8.4, 10.6, 11.5, 11.6, 12.3, 12.4, 13.4,
     * 15.1). The configured page size is used.
     *
     * @param rawQuery the raw user query text; must not be null
     * @param page     the requested 1-based page number ({@code < 1} =&gt; invalidPage, Req 11.6)
     * @return the assembled results page with its mapped status
     */
    public SearchResultsPage search(String rawQuery, int page) {
        Objects.requireNonNull(rawQuery, "rawQuery must not be null");

        long startMillis = clock.nowMillis();

        // Stage 1 — admission / rate limit (Req 12.3). Rejected queries do no downstream work.
        if (!admissionController.tryAdmit(startMillis)) {
            return emit(SearchResultsPage.empty(PageStatus.OVERLOADED, page), startMillis);
        }

        // Stage 2 — parse (Req 7.4). Empty-after-normalization short-circuits.
        StructuredQuery query = queryProcessor.parse(rawQuery);
        if (queryProcessor.isEmpty(query)) {
            return emit(SearchResultsPage.empty(PageStatus.NO_QUERY_TERMS, page), startMillis);
        }

        // Stage 3 — candidate retrieval with per-shard deadline + replica failover (Req 8.x/12.4/13.4).
        ShardFanOut fanOut = fanOut(query);
        RetrievalResult retrieval = fanOut.retrieval();

        // Stage 4 — ranking (Req 10.6: empty candidates -> empty page, no error).
        List<RankingCandidate> rankingCandidates = new ArrayList<>(retrieval.candidates().size());
        for (Candidate candidate : retrieval.candidates()) {
            rankingCandidates.add(rankingComponents.forCandidate(candidate));
        }
        RankingResult ranked = ranker.rank(rankingCandidates);
        List<RankedResult> orderedResults = ranked.ranked();

        // Stage 5 — pagination + Result_Entry assembly (Req 11.5, 11.6, 8.4).
        SearchResultsPage paged =
                Paginator.paginate(orderedResults, page, config.pageSize(), metadataResolver);

        // Stage 6 — status mapping (coverage overrides OK/NO_MATCHES; Req 12.4/13.4) + metrics (15.1).
        SearchResultsPage mapped = mapStatus(paged, orderedResults, fanOut, page);
        return emit(mapped, startMillis);
    }

    // --- retrieval fan-out -------------------------------------------------------------------

    /**
     * Fans out to every registered shard, selecting a healthy replica and opening its posting source
     * under the per-shard deadline. Classifies each shard as available, no-replica (Req 13.4), or
     * timed out (Req 12.4), then runs {@link CandidateRetrieval#retrieve} over the responders.
     */
    private ShardFanOut fanOut(StructuredQuery query) {
        List<Supplier<ShardPostingSource>> sources = new ArrayList<>();
        int noReplicaShards = 0; // Req 13.4
        int timedOutShards = 0;  // Req 12.4

        for (ShardId shardId : controlPlane.registeredShards()) {
            ReplicaSelection selection = controlPlane.pickHealthyReplica(shardId);
            if (!selection.isPresent()) {
                // No healthy replica for the shard: it cannot be covered (Req 13.4).
                noReplicaShards++;
                continue;
            }
            ShardQueryPlan.Outcome outcome =
                    shardQueryPlan.openSource(shardId, config.perShardDeadline());
            switch (outcome.kind()) {
                case AVAILABLE -> {
                    ShardPostingSource source = outcome.source();
                    sources.add(() -> source);
                }
                case NO_HEALTHY_REPLICA ->
                    // Replica health changed between selection and access: treat as uncovered (Req 13.4).
                    noReplicaShards++;
                case TIMED_OUT ->
                    // Selected replica missed the per-shard deadline: partial result (Req 12.4).
                    timedOutShards++;
            }
        }

        RetrievalResult retrieval = CandidateRetrieval.retrieve(query, sources);
        return new ShardFanOut(retrieval, noReplicaShards, timedOutShards);
    }

    /** The result of fanning out to the shards plus the two non-responding-shard tallies. */
    private record ShardFanOut(RetrievalResult retrieval, int noReplicaShards, int timedOutShards) {
        /** Total shards that did not contribute results (Req 12.4/13.4). */
        int nonRespondingShards() {
            return noReplicaShards + timedOutShards;
        }
    }

    // --- status mapping ----------------------------------------------------------------------

    /**
     * Maps the paginator's page onto the final Serving_Layer status, layering shard-coverage
     * conditions over the pagination outcome (Req 8.4, 11.5, 11.6, 12.4, 13.4).
     *
     * <p>{@code invalidPage} and {@code noFurtherResults} are terminal outcomes of the requested
     * page and are preserved unchanged. For an {@code OK} or {@code noMatches} page, a shard with no
     * healthy replica takes precedence and yields {@code incompleteShardCoverage} (Req 13.4);
     * otherwise a timed-out shard yields {@code partialResult} (Req 12.4). When any shard did not
     * respond, the non-responding shard count is carried as {@code partialShardCount}.
     */
    private static SearchResultsPage mapStatus(
            SearchResultsPage paged, List<RankedResult> orderedResults, ShardFanOut fanOut, int page) {

        PageStatus pagedStatus = paged.status();

        // Terminal pagination outcomes are not overridden by coverage.
        if (pagedStatus == PageStatus.INVALID_PAGE || pagedStatus == PageStatus.NO_FURTHER_RESULTS) {
            return paged;
        }

        int nonResponding = fanOut.nonRespondingShards();
        if (nonResponding == 0) {
            // Full coverage: honour OK vs noMatches (Req 8.4).
            if (orderedResults.isEmpty()) {
                return SearchResultsPage.empty(PageStatus.NO_MATCHES, page);
            }
            return paged; // OK page with entries
        }

        // Coverage is degraded: any uncovered shard (no healthy replica) is incomplete-shard-coverage
        // (Req 13.4); otherwise a timed-out shard is a partial result (Req 12.4). Carry the count.
        PageStatus coverageStatus = fanOut.noReplicaShards() > 0
                ? PageStatus.INCOMPLETE_SHARD_COVERAGE
                : PageStatus.PARTIAL_RESULT;
        return SearchResultsPage.partial(paged.entries(), coverageStatus, page, nonResponding);
    }

    // --- metric emission (Req 15.1) ----------------------------------------------------------

    /**
     * Emits the per-query metric (latency + result count, Req 15.1) and returns the page unchanged.
     * Recording is delegated to {@link Observability}, which never lets a recording failure abort
     * the response (Req 15.6).
     */
    private SearchResultsPage emit(SearchResultsPage page, long startMillis) {
        long now = clock.nowMillis();
        long latency = Math.max(0L, now - startMillis);
        observability.recordQueryMetric(new QueryMetric(latency, page.entries().size(), now));
        return page;
    }

    // --- builder -----------------------------------------------------------------------------

    /** Builder wiring the Serving_Layer collaborators (design §6). */
    public static final class Builder {
        private AdmissionController admissionController;
        private QueryProcessor queryProcessor = new QueryProcessor();
        private ShardReplicationControlPlane controlPlane;
        private ShardQueryPlan shardQueryPlan;
        private Ranker ranker = Ranker.withDefaultWeights();
        private RankingComponents rankingComponents;
        private MetadataResolver metadataResolver;
        private Observability observability;
        private Observability.Clock clock = System::currentTimeMillis;
        private ServingConfig config = ServingConfig.defaults();

        private Builder() {
        }

        public Builder admissionController(AdmissionController v) {
            this.admissionController = v;
            return this;
        }

        public Builder queryProcessor(QueryProcessor v) {
            this.queryProcessor = v;
            return this;
        }

        public Builder controlPlane(ShardReplicationControlPlane v) {
            this.controlPlane = v;
            return this;
        }

        public Builder shardQueryPlan(ShardQueryPlan v) {
            this.shardQueryPlan = v;
            return this;
        }

        public Builder ranker(Ranker v) {
            this.ranker = v;
            return this;
        }

        public Builder rankingComponents(RankingComponents v) {
            this.rankingComponents = v;
            return this;
        }

        public Builder metadataResolver(MetadataResolver v) {
            this.metadataResolver = v;
            return this;
        }

        public Builder observability(Observability v) {
            this.observability = v;
            return this;
        }

        public Builder clock(Observability.Clock v) {
            this.clock = v;
            return this;
        }

        public Builder config(ServingConfig v) {
            this.config = v;
            return this;
        }

        public ServingLayer build() {
            return new ServingLayer(this);
        }
    }
}
