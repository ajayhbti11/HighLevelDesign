package com.searchengine.observability;

import java.util.List;

/**
 * Pluggable backing store for recorded metrics and error records (design §Cross-Cutting
 * "Observability").
 *
 * <p>Implementations persist the records and make them queryable by component name and time
 * range for the configured retention period (Req 15.5). Any write method may throw: a durable
 * sink can be temporarily unavailable, out of space, or misconfigured. {@link Observability}
 * isolates callers from those failures so that a recording failure never aborts the primary
 * operation (Req 15.6).
 *
 * <p>Because the {@link #recordRecordingFailure(ErrorRecord)} path is used to report that a prior
 * write failed, implementations should make it as robust as possible; if it also throws,
 * {@link Observability} swallows the secondary failure rather than propagating it.
 */
public interface MetricSink {

    /** Persists a per-query metric (Req 15.1). May throw if the store is unavailable. */
    void recordQueryMetric(QueryMetric metric);

    /** Persists a crawl-cycle metric (Req 15.2). May throw if the store is unavailable. */
    void recordCrawlCycle(CrawlCycleMetric metric);

    /** Persists an index-cycle metric (Req 15.3). May throw if the store is unavailable. */
    void recordIndexCycle(IndexCycleMetric metric);

    /** Persists an error record (Req 15.4). May throw if the store is unavailable. */
    void recordError(ErrorRecord error);

    /**
     * Persists a recording-failure indication (Req 15.6): an error record noting that a prior
     * metric or error write failed. Implementations should treat this as best-effort.
     */
    void recordRecordingFailure(ErrorRecord indication);

    /**
     * Returns the error records for a component whose timestamps fall within the inclusive time
     * range {@code [fromMillis, toMillis]} (Req 15.5).
     *
     * @param component  the component name to filter by
     * @param fromMillis inclusive lower bound of the timestamp range, in epoch milliseconds
     * @param toMillis   inclusive upper bound of the timestamp range, in epoch milliseconds
     * @return the matching error records; never {@code null}
     */
    List<ErrorRecord> queryErrors(String component, long fromMillis, long toMillis);
}
