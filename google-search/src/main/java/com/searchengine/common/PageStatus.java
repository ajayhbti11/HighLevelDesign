package com.searchengine.common;

/**
 * PageStatus: the Search_Results_Page-level status (design §6, Req 7.4, 8.4, 11, 12, 13).
 *
 * <ul>
 *   <li>{@link #OK} - a normal results page.</li>
 *   <li>{@link #NO_QUERY_TERMS} - the query was empty after normalization (Req 7.4).</li>
 *   <li>{@link #NO_MATCHES} - no Document satisfied the query constraints (Req 8.4).</li>
 *   <li>{@link #NO_FURTHER_RESULTS} - the requested page is beyond the last results (Req 11.5).</li>
 *   <li>{@link #INVALID_PAGE} - the requested page number is &lt; 1 or not an integer (Req 11.6).</li>
 *   <li>{@link #OVERLOADED} - the query rate exceeded the configured peak (Req 12.3).</li>
 *   <li>{@link #PARTIAL_RESULT} - one or more shards did not respond in time (Req 12.4).</li>
 *   <li>{@link #INCOMPLETE_SHARD_COVERAGE} - all replicas of a shard were unavailable (Req 13.4).</li>
 * </ul>
 */
public enum PageStatus {
    OK,
    NO_QUERY_TERMS,
    NO_MATCHES,
    NO_FURTHER_RESULTS,
    INVALID_PAGE,
    OVERLOADED,
    PARTIAL_RESULT,
    INCOMPLETE_SHARD_COVERAGE
}
