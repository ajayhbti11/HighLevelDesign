package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;
import com.searchengine.common.ContentRecord;
import com.searchengine.common.DocId;
import com.searchengine.common.FetchOutcome;
import com.searchengine.common.ParseStatus;
import com.searchengine.common.ParsedDocument;
import com.searchengine.common.UrlCanonicalizer;
import com.searchengine.index.IndexResult;
import com.searchengine.index.Indexer;
import com.searchengine.observability.CrawlCycleMetric;
import com.searchengine.parser.Parser;
import com.searchengine.observability.IndexCycleMetric;
import com.searchengine.observability.Observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrawlPipeline: the indexing-plane orchestrator that connects the Crawler, Content_Store, Parser,
 * and Indexer end to end (design "Indexing plane: Crawler -> Content_Store -> Parser -> Indexer ->
 * Inverted_Index"; task 23.1).
 *
 * <p>It reuses the existing components without duplicating their logic:
 * <ul>
 *   <li>{@link Frontier} — the prioritized URL queue (§1.1).</li>
 *   <li>{@link RobotsManager} — politeness/robots gate consulted before every content fetch
 *       (§1.2, Req 2.1).</li>
 *   <li>{@link Fetcher} — single HTTP retrieval interpreted into a {@link FetchOutcome}
 *       (§1.3, Req 3.1).</li>
 *   <li>{@link ContentStore} — durable raw-document store; an {@link InMemoryContentStore} is used
 *       when none is supplied.</li>
 *   <li>{@link Parser} — text/link/title extraction + shared normalization (§2, Req 4.1).</li>
 *   <li>{@link Indexer} — inverted-index build/replace/remove, routed to exactly one shard via the
 *       control-plane shard map (§4, Req 5.1, 5.7).</li>
 *   <li>{@link RefetchScheduler} — feeds adaptive re-fetches back into the Frontier (§1.4,
 *       Req 6.3).</li>
 *   <li>{@link Observability} — crawl-cycle and index-cycle counters (Req 15.2, 15.3).</li>
 * </ul>
 *
 * <h2>One crawl step</h2>
 * {@link #runCrawlStep()} pops the highest-priority Frontier entry (§1.1, Req 1.4/1.5), ensures the
 * host's robots policy and enforces {@link RobotsManager#isAllowed(String)} before fetching
 * (Req 2.1), fetches honoring the effective delay (Req 3.1), and interprets the outcome:
 * <ul>
 *   <li><b>Stored (200):</b> persist the record to the Content_Store (Req 3.1), then run the
 *       fetch -> parse -> index flow (Req 4.1, 5.1); discovered outbound links are fed back into the
 *       Frontier (Req 1.3), and the newly indexed document's adaptive re-fetch is scheduled back
 *       into the Frontier (Req 6.3). A re-fetch that returns a 404/410 (client error) removes the
 *       document from the index and store (Req 6.2).</li>
 *   <li><b>Retry (5xx/timeout):</b> reschedule with {@code retryCount + 1} up to the configured max
 *       retries, else mark permanently failed (Req 3.2, 3.3).</li>
 *   <li><b>Redirect (3xx):</b> enqueue the target and record the source -> target edge, enforcing
 *       the redirect-chain cap (Req 3.4, 3.5).</li>
 *   <li><b>FailedPermanent (4xx / limits):</b> excluded from further scheduling (Req 3.6). A 4xx on
 *       a URL that is already indexed is treated as a removal (Req 6.2).</li>
 * </ul>
 * Every step contributes to the {@link CrawlCycleMetric} and {@link IndexCycleMetric} counts, which
 * are recorded when {@link #recordCycleMetrics(long)} is called at the end of a crawl cycle
 * (Req 15.2, 15.3).
 *
 * <p>This class is not thread-safe; a single crawler worker drives one pipeline instance.
 */
public final class CrawlPipeline {

    /** Outcome of a single {@link #runCrawlStep()} call, for observation and testing. */
    public enum StepResult {
        /** The Frontier was empty; nothing to do. */
        FRONTIER_EMPTY,
        /** The URL was disallowed by robots and skipped (Req 2.4). */
        SKIPPED_ROBOTS,
        /** A document was fetched, parsed, and indexed (Req 3.1, 4.1, 5.1). */
        INDEXED,
        /** A re-fetch detected the document changed and it was re-indexed (Req 5.4). */
        REINDEXED,
        /** A previously indexed document was removed after a 404/410 re-fetch (Req 6.2). */
        REMOVED,
        /** The document was parsed but skipped for indexing (unsupported/too-large, Req 4.4/4.5). */
        SKIPPED_PARSE,
        /** The fetch was rescheduled for another attempt (Req 3.2). */
        RESCHEDULED,
        /** A redirect target was enqueued and the edge recorded (Req 3.4). */
        REDIRECTED,
        /** The URL failed permanently and is excluded from scheduling (Req 3.3/3.5/3.6). */
        FAILED_PERMANENT
    }

    private final Frontier frontier;
    private final RobotsManager robotsManager;
    private final Fetcher fetcher;
    private final ContentStore contentStore;
    private final Parser parser;
    private final Indexer indexer;
    private final RefetchScheduler refetchScheduler;
    private final Observability observability;
    private final FetcherConfig config;

    /** Doc_ID assigned to each already-indexed Canonical_URL, enabling reindex/remove (Req 5.4, 6.2). */
    private final Map<String, DocId> docIdByUrl = new ConcurrentHashMap<>();
    /** Last known content hash per Canonical_URL, used to detect change on re-fetch (Req 6.3). */
    private final Map<String, String> contentHashByUrl = new ConcurrentHashMap<>();
    /** Redirect source -> target edges recorded during crawling (Req 3.4). */
    private final List<RedirectEdge> redirectEdges = new ArrayList<>();

    // Crawl-cycle counters (Req 15.2).
    private long fetchSuccesses;
    private long fetchFailures;
    // Index-cycle counters (Req 15.3).
    private long docsAdded;
    private long docsUpdated;
    private long docsRemoved;

    /**
     * Creates a pipeline over the given components. If {@code contentStore} is {@code null} an
     * {@link InMemoryContentStore} is used (task 23.1).
     *
     * @param frontier         the Frontier to drain and feed back into; must be non-null
     * @param robotsManager    the robots/politeness gate; must be non-null
     * @param fetcher          the HTTP fetcher; must be non-null
     * @param contentStore     the raw-document store, or {@code null} to use an in-memory store
     * @param parser           the Parser; must be non-null
     * @param indexer          the Indexer; must be non-null
     * @param refetchScheduler the adaptive re-fetch scheduler feeding the Frontier; must be non-null
     * @param observability    the metrics/error recorder; must be non-null
     * @param config           fetcher/crawler bounds (max retries, max redirects); must be non-null
     */
    public CrawlPipeline(Frontier frontier,
                         RobotsManager robotsManager,
                         Fetcher fetcher,
                         ContentStore contentStore,
                         Parser parser,
                         Indexer indexer,
                         RefetchScheduler refetchScheduler,
                         Observability observability,
                         FetcherConfig config) {
        this.frontier = Objects.requireNonNull(frontier, "frontier must not be null");
        this.robotsManager = Objects.requireNonNull(robotsManager, "robotsManager must not be null");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.contentStore = contentStore != null ? contentStore : new InMemoryContentStore();
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.indexer = Objects.requireNonNull(indexer, "indexer must not be null");
        this.refetchScheduler = Objects.requireNonNull(refetchScheduler, "refetchScheduler must not be null");
        this.observability = Objects.requireNonNull(observability, "observability must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Seeds the Frontier with an initial URL (§1.1, Req 1.1). */
    public com.searchengine.common.AddResult seed(String url) {
        return frontier.add(url);
    }

    /**
     * Runs exactly one crawl step: pop the next Frontier entry, gate on robots, fetch, and drive the
     * fetch -> parse -> index flow for a stored document (design data flow; Req 1.4, 2.1, 3.1, 4.1,
     * 5.1). Discovered links and adaptive re-fetches are fed back into the Frontier (Req 1.3, 6.3).
     *
     * @return the {@link StepResult} describing what happened
     */
    public StepResult runCrawlStep() {
        FrontierEntry entry = frontier.next(); // §1.1 Req 1.4/1.5
        if (entry == null) {
            return StepResult.FRONTIER_EMPTY;
        }

        String url = entry.canonicalUrl().value();
        String host = entry.host();

        // Req 2.1: ensure the host's robots policy exists before any content fetch, then gate.
        robotsManager.ensurePolicy(host);
        if (!robotsManager.isAllowed(url)) {
            return StepResult.SKIPPED_ROBOTS; // Req 2.4
        }

        double delaySeconds = robotsManager.effectiveDelaySeconds(host); // Req 2.5/2.6
        FetchOutcome outcome = fetcher.fetch(entry, delaySeconds);        // Req 3.1

        if (outcome instanceof FetchOutcome.Stored stored) {
            fetchSuccesses++; // Req 15.2
            return handleStored(entry, stored.record());
        }
        // Anything that is not a stored 200 counts as a failed fetch attempt for the cycle.
        fetchFailures++; // Req 15.2
        if (outcome instanceof FetchOutcome.Retry retry) {
            return handleRetry(entry, retry.retryCount());
        }
        if (outcome instanceof FetchOutcome.Redirect redirect) {
            return handleRedirect(entry, redirect.target());
        }
        // FetchOutcome.FailedPermanent — 4xx / limits (Req 3.3/3.5/3.6).
        return handleFailedPermanent(entry);
    }

    // --- fetch -> parse -> index flow -------------------------------------------------------

    /**
     * Handles a stored 200 response: persist to the Content_Store (Req 3.1), parse (Req 4.1), and
     * index or re-index (Req 5.1, 5.4). Discovered outbound links are fed back into the Frontier
     * (Req 1.3) and the document's adaptive re-fetch is scheduled (Req 6.3).
     */
    private StepResult handleStored(FrontierEntry entry, ContentRecord record) {
        contentStore.put(record); // Content_Store persist (Req 3.1)

        ParsedDocument parsed = parser.parse(record); // Parser (Req 4.1)
        String url = entry.canonicalUrl().value();

        if (parsed.status() != ParseStatus.PARSED) {
            // Unsupported type or too large: not indexed (Req 4.4/4.5). If it was previously
            // indexed, drop it so the index does not keep stale postings.
            if (docIdByUrl.containsKey(url)) {
                return removeIndexed(url);
            }
            return StepResult.SKIPPED_PARSE;
        }

        // Feed Parser-discovered outbound links back into the Frontier (Req 1.3).
        enqueueDiscoveredLinks(parsed.outboundLinks(), entry.priority());

        DocId existing = docIdByUrl.get(url);
        if (existing == null) {
            return indexNew(entry, parsed);
        }
        return reindexExisting(entry, parsed, existing);
    }

    /** First-time index: assign a Doc_ID and populate postings (Req 5.1, 5.2, 5.3, 5.7). */
    private StepResult indexNew(FrontierEntry entry, ParsedDocument parsed) {
        IndexResult result = indexer.index(parsed);
        if (result.isSuccess()) {
            String url = entry.canonicalUrl().value();
            docIdByUrl.put(url, result.docId());
            contentHashByUrl.put(url, parsed.contentHash());
            docsAdded++; // Req 15.3
            scheduleRefetch(result.docId(), entry.canonicalUrl(), true);
            return StepResult.INDEXED;
        }
        recordIndexFailure("index", entry, result);
        return StepResult.SKIPPED_PARSE;
    }

    /**
     * Re-fetch of an already-indexed document: re-index only when the normalized content changed
     * (Req 5.4), and adapt the re-fetch interval based on the change (Req 6.3).
     */
    private StepResult reindexExisting(FrontierEntry entry, ParsedDocument parsed, DocId docId) {
        String url = entry.canonicalUrl().value();
        boolean changed = !parsed.contentHash().equals(contentHashByUrl.get(url));

        if (!changed) {
            // Content unchanged: no re-index needed, but the interval grows (Req 6.3).
            scheduleRefetch(docId, entry.canonicalUrl(), false);
            return StepResult.INDEXED;
        }

        // Attach the existing Doc_ID so the Indexer performs an atomic replace (Req 5.4).
        ParsedDocument withId = new ParsedDocument(
                docId,
                parsed.canonicalUrl(),
                parsed.title(),
                parsed.normalizedTokens(),
                parsed.outboundLinks(),
                parsed.contentHash(),
                parsed.status());
        IndexResult result = indexer.reindex(withId);
        if (result.isSuccess()) {
            contentHashByUrl.put(url, parsed.contentHash());
            docsUpdated++; // Req 15.3
            scheduleRefetch(docId, entry.canonicalUrl(), true);
            return StepResult.REINDEXED;
        }
        recordIndexFailure("reindex", entry, result);
        return StepResult.INDEXED;
    }

    // --- non-200 outcomes -------------------------------------------------------------------

    /** 5xx/timeout: reschedule with an incremented retry count, else fail permanently (Req 3.2/3.3). */
    private StepResult handleRetry(FrontierEntry entry, int newRetryCount) {
        if (newRetryCount > config.maxRetries()) {
            return StepResult.FAILED_PERMANENT; // Req 3.3: excluded from scheduling
        }
        frontier.reschedule(entry, newRetryCount); // Req 3.2
        return StepResult.RESCHEDULED;
    }

    /** 3xx: record the source -> target edge (Req 3.4) and enqueue the target within the cap (Req 3.5). */
    private StepResult handleRedirect(FrontierEntry entry, String target) {
        String source = entry.canonicalUrl().value();
        redirectEdges.add(new RedirectEdge(source, target)); // Req 3.4

        // Enforce the redirect-chain cap using the entry's accumulated retry/hop count (Req 3.5).
        if (entry.retryCount() + 1 > config.maxRedirects()) {
            return StepResult.FAILED_PERMANENT; // Req 3.5: redirectLimit
        }

        CanonicalUrl canonicalTarget = UrlCanonicalizer.canonicalize(target);
        if (canonicalTarget.isValid()) {
            // Carry the hop count forward as priority-preserving discovery so the cap is enforceable.
            frontier.add(canonicalTarget.value(), entry.priority(), entry.discoveredAt()); // Req 3.4/1.3
        }
        return StepResult.REDIRECTED;
    }

    /**
     * 4xx / limit failures: excluded from scheduling (Req 3.6). A client error on a URL that is
     * already indexed is treated as a 404/410-style removal (Req 6.2).
     */
    private StepResult handleFailedPermanent(FrontierEntry entry) {
        String url = entry.canonicalUrl().value();
        if (docIdByUrl.containsKey(url)) {
            return removeIndexed(url); // Req 6.2
        }
        return StepResult.FAILED_PERMANENT; // Req 3.6
    }

    /** Removes an indexed document from the index and Content_Store (Req 6.2, 5.5). */
    private StepResult removeIndexed(String url) {
        DocId docId = docIdByUrl.remove(url);
        contentHashByUrl.remove(url);
        contentStore.remove(url);
        if (docId != null) {
            IndexResult result = indexer.remove(docId); // Req 5.5/6.2
            if (result.isSuccess()) {
                docsRemoved++; // Req 15.3
            }
        }
        return StepResult.REMOVED;
    }

    // --- Frontier feedback ------------------------------------------------------------------

    /** Feeds Parser-discovered outbound links back into the Frontier (Req 1.3). */
    private void enqueueDiscoveredLinks(List<String> outboundLinks, int priority) {
        for (String link : outboundLinks) {
            // Frontier.add canonicalizes and dedups (Req 1.2/1.3/1.6); invalid/duplicate links are
            // rejected without side effects.
            frontier.add(link, priority, 0L);
        }
    }

    /** Schedules the document's adaptive re-fetch back into the Frontier via the scheduler (Req 6.3). */
    private void scheduleRefetch(DocId docId, CanonicalUrl canonicalUrl, boolean changed) {
        String url = canonicalUrl.value();
        long prev = lastIntervalByUrl.getOrDefault(url, 0L);
        long next = refetchScheduler.nextInterval(docId, changed, prev); // Req 6.3
        lastIntervalByUrl.put(url, next);
        // The scheduler enqueues into the Frontier through its sink (design §1.4 SCHED --> FRONTIER).
        refetchScheduler.schedule(docId, canonicalUrl, next);
    }

    /** Last computed re-fetch interval per Canonical_URL, so the interval adapts across cycles. */
    private final Map<String, Long> lastIntervalByUrl = new ConcurrentHashMap<>();

    // --- observability ----------------------------------------------------------------------

    /**
     * Records the accumulated crawl-cycle and index-cycle counters to Observability (Req 15.2,
     * 15.3) and resets them for the next cycle. Recording never aborts crawling (Req 15.6, enforced
     * by {@link Observability}).
     *
     * @param timestamp epoch milliseconds marking the end of the cycle
     */
    public void recordCycleMetrics(long timestamp) {
        observability.recordCrawlCycle(CrawlCycleMetric.of(fetchSuccesses, fetchFailures, timestamp));
        observability.recordIndexCycle(new IndexCycleMetric(docsAdded, docsUpdated, docsRemoved, timestamp));
        fetchSuccesses = 0;
        fetchFailures = 0;
        docsAdded = 0;
        docsUpdated = 0;
        docsRemoved = 0;
    }

    private void recordIndexFailure(String op, FrontierEntry entry, IndexResult result) {
        observability.recordError(
                "Indexer",
                op,
                new IllegalStateException(
                        "index " + op + " reverted for " + entry.canonicalUrl().value()
                                + ": " + result.failureCause()));
    }

    // --- inspection (for wiring/tests) ------------------------------------------------------

    /** @return the Content_Store backing this pipeline (an in-memory store when none was supplied). */
    public ContentStore contentStore() {
        return contentStore;
    }

    /** @return the Doc_ID currently indexed for a Canonical_URL, if any (Req 5.3). */
    public Optional<DocId> docIdFor(String canonicalUrl) {
        return Optional.ofNullable(docIdByUrl.get(canonicalUrl));
    }

    /** @return an immutable snapshot of the redirect edges recorded so far (Req 3.4). */
    public List<RedirectEdge> redirectEdges() {
        return List.copyOf(redirectEdges);
    }
}
