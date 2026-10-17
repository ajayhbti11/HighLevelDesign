package com.searchengine.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default in-memory {@link MetricSink} used by tests and single-node runs.
 *
 * <p>Retains every recorded metric and error record and makes error records queryable by
 * component name and time range (Req 15.5). It is thread-safe via copy-on-write lists so it can
 * back concurrent recording from the crawl, index, and serving planes without locking on the
 * recording hot path.
 *
 * <p>This sink accepts every write and never throws, so on its own it exercises the success path.
 * Recording-failure handling (Req 15.6) is tested by wrapping {@link Observability} around a sink
 * whose write methods are configured to throw; the recording-failure indications land here via
 * {@link #recordRecordingFailure(ErrorRecord)}.
 */
public final class InMemoryMetricSink implements MetricSink {

    private final List<QueryMetric> queryMetrics = new CopyOnWriteArrayList<>();
    private final List<CrawlCycleMetric> crawlCycles = new CopyOnWriteArrayList<>();
    private final List<IndexCycleMetric> indexCycles = new CopyOnWriteArrayList<>();
    private final List<ErrorRecord> errors = new CopyOnWriteArrayList<>();
    private final List<ErrorRecord> recordingFailures = new CopyOnWriteArrayList<>();

    @Override
    public void recordQueryMetric(QueryMetric metric) {
        queryMetrics.add(Objects.requireNonNull(metric, "metric must not be null"));
    }

    @Override
    public void recordCrawlCycle(CrawlCycleMetric metric) {
        crawlCycles.add(Objects.requireNonNull(metric, "metric must not be null"));
    }

    @Override
    public void recordIndexCycle(IndexCycleMetric metric) {
        indexCycles.add(Objects.requireNonNull(metric, "metric must not be null"));
    }

    @Override
    public void recordError(ErrorRecord error) {
        errors.add(Objects.requireNonNull(error, "error must not be null"));
    }

    @Override
    public void recordRecordingFailure(ErrorRecord indication) {
        recordingFailures.add(Objects.requireNonNull(indication, "indication must not be null"));
    }

    @Override
    public List<ErrorRecord> queryErrors(String component, long fromMillis, long toMillis) {
        Objects.requireNonNull(component, "component must not be null");
        List<ErrorRecord> matches = new ArrayList<>();
        for (ErrorRecord e : errors) {
            if (e.component().equals(component)
                    && e.timestamp() >= fromMillis
                    && e.timestamp() <= toMillis) {
                matches.add(e);
            }
        }
        return List.copyOf(matches);
    }

    /** Immutable snapshot of all recorded per-query metrics, in recording order. */
    public List<QueryMetric> queryMetrics() {
        return List.copyOf(queryMetrics);
    }

    /** Immutable snapshot of all recorded crawl-cycle metrics, in recording order. */
    public List<CrawlCycleMetric> crawlCycles() {
        return List.copyOf(crawlCycles);
    }

    /** Immutable snapshot of all recorded index-cycle metrics, in recording order. */
    public List<IndexCycleMetric> indexCycles() {
        return List.copyOf(indexCycles);
    }

    /** Immutable snapshot of all recorded error records, in recording order. */
    public List<ErrorRecord> errors() {
        return List.copyOf(errors);
    }

    /** Immutable snapshot of all recorded recording-failure indications (Req 15.6). */
    public List<ErrorRecord> recordingFailures() {
        return List.copyOf(recordingFailures);
    }
}
