package com.searchengine.observability;

import java.util.Objects;

/**
 * Observability recorder facade: the single entry point components use to record metrics and
 * error records (design §Cross-Cutting "Observability", Requirements 15.1-15.4, 15.6).
 *
 * <p>Every recording call delegates to the underlying {@link MetricSink} but is wrapped so that a
 * sink failure is caught and converted into a recording-failure indication rather than
 * propagating to the caller. This enforces the central invariant that <b>recording never aborts
 * the primary operation</b> (Req 15.6): a component calls one of these methods after (or as part
 * of) completing its primary work, and the call always returns normally regardless of whether the
 * backing store accepted the write.
 *
 * <p>All recording methods are non-throwing by contract. Only the argument-validation guards
 * (rejecting {@code null} records) throw, since those indicate a programming error in the caller
 * rather than a recording-store failure.
 *
 * <p>A {@link Clock} abstraction supplies timestamps so callers that do not already have one can
 * record error records without threading the current time through every layer, and so tests can
 * make timestamps deterministic.
 */
public final class Observability {

    /** Component name used for the recording-failure indications this facade emits itself. */
    static final String SELF_COMPONENT = "Observability";

    /** Supplies the current epoch-millisecond time; injectable for deterministic tests. */
    @FunctionalInterface
    public interface Clock {
        long nowMillis();
    }

    private final MetricSink sink;
    private final Clock clock;

    /**
     * Creates a recorder over the given sink using the system wall clock.
     *
     * @param sink the backing store for metrics and error records; non-null
     */
    public Observability(MetricSink sink) {
        this(sink, System::currentTimeMillis);
    }

    /**
     * Creates a recorder over the given sink and clock.
     *
     * @param sink  the backing store for metrics and error records; non-null
     * @param clock supplies timestamps for error records; non-null
     */
    public Observability(MetricSink sink, Clock clock) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records a per-query metric with the measured latency and returned result count (Req 15.1).
     * Never throws on a sink failure.
     *
     * @param metric the per-query metric; non-null
     */
    public void recordQueryMetric(QueryMetric metric) {
        Objects.requireNonNull(metric, "metric must not be null");
        guarded("recordQueryMetric", () -> sink.recordQueryMetric(metric));
    }

    /**
     * Records the fetch-attempt, success, and failure counts for a completed crawl cycle
     * (Req 15.2). Never throws on a sink failure.
     *
     * @param metric the crawl-cycle metric; non-null
     */
    public void recordCrawlCycle(CrawlCycleMetric metric) {
        Objects.requireNonNull(metric, "metric must not be null");
        guarded("recordCrawlCycle", () -> sink.recordCrawlCycle(metric));
    }

    /**
     * Records the added, updated, and removed counts for a completed indexing cycle (Req 15.3).
     * Never throws on a sink failure.
     *
     * @param metric the index-cycle metric; non-null
     */
    public void recordIndexCycle(IndexCycleMetric metric) {
        Objects.requireNonNull(metric, "metric must not be null");
        guarded("recordIndexCycle", () -> sink.recordIndexCycle(metric));
    }

    /**
     * Records an error record for a failed component operation (Req 15.4). Never throws on a sink
     * failure.
     *
     * @param error the error record; non-null
     */
    public void recordError(ErrorRecord error) {
        Objects.requireNonNull(error, "error must not be null");
        guarded("recordError", () -> sink.recordError(error));
    }

    /**
     * Convenience overload that builds and records an error record from a {@link Throwable}, using
     * the recorder's clock for the timestamp (Req 15.4). Never throws on a sink failure.
     *
     * @param component the name of the component whose operation failed
     * @param operation the name of the failing operation
     * @param cause     the exception describing the failure; non-null
     */
    public void recordError(String component, String operation, Throwable cause) {
        recordError(ErrorRecord.fromThrowable(component, operation, cause, clock.nowMillis()));
    }

    /**
     * Runs a recording action, catching any failure and turning it into a recording-failure
     * indication (Req 15.6). The primary operation that called into this recorder is unaffected:
     * this method always returns normally.
     *
     * <p>If emitting the recording-failure indication also fails, the secondary failure is
     * swallowed so that the guarantee holds unconditionally.
     */
    private void guarded(String operation, Runnable recordingAction) {
        try {
            recordingAction.run();
        } catch (RuntimeException | Error primaryFailure) {
            ErrorRecord indication = ErrorRecord.fromThrowable(
                    SELF_COMPONENT, operation, primaryFailure, clock.nowMillis());
            try {
                sink.recordRecordingFailure(indication);
            } catch (RuntimeException | Error ignored) {
                // Best-effort: never let a recording failure (or its indication) abort the caller.
            }
        }
    }
}
