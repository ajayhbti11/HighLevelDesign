/**
 * Observability: metrics and error records with retention (design §Cross-Cutting "Observability",
 * Requirements 15).
 *
 * <p>This package records the operational signals the search engine emits:
 * per-query latency and result-count metrics (Req 15.1), crawl-cycle counts (Req 15.2),
 * index-cycle counts (Req 15.3), and structured error records carrying component, operation,
 * cause, and timestamp (Req 15.4).
 *
 * <p>The central invariant is <b>non-aborting recording</b> (Req 15.6): recording is a
 * cross-cutting side effect that must never take down the primary operation that triggered it.
 * {@link com.searchengine.observability.Observability} therefore wraps every write to the
 * underlying {@link com.searchengine.observability.MetricSink} so that a sink failure is caught
 * and turned into a recording-failure indication rather than propagating to the caller.
 *
 * <p>{@link com.searchengine.observability.InMemoryMetricSink} is the default queryable store
 * used by tests and single-node runs; production deployments supply a durable sink honoring the
 * configured retention period and querying by component name and time range (Req 15.5).
 */
package com.searchengine.observability;
