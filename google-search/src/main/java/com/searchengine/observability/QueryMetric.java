package com.searchengine.observability;

/**
 * Per-query metric recorded when the Serving_Layer returns a Search_Results_Page (Req 15.1).
 *
 * <p>Captures the measured Query_Latency in milliseconds and the number of Result_Entry items
 * returned for that query, along with the epoch-millisecond timestamp at which the query was
 * served (used for retention and time-range querying, Req 15.5).
 *
 * @param latencyMillis the measured Query_Latency in milliseconds (non-negative)
 * @param resultCount   the number of Result_Entry items returned (non-negative)
 * @param timestamp     epoch milliseconds at which the query was served
 */
public record QueryMetric(long latencyMillis, int resultCount, long timestamp) {

    public QueryMetric {
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must be non-negative, got: " + latencyMillis);
        }
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must be non-negative, got: " + resultCount);
        }
    }
}
