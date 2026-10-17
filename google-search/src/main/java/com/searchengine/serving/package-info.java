/**
 * Serving layer: pagination and Result_Entry assembly (design §6, §H "Pagination"; Req 11).
 *
 * <p>Given the ranker's strict total order of {@code RankedResult}s, the {@link
 * com.searchengine.serving.Paginator} returns the requested contiguous, disjoint slice of at most
 * {@code pageSize} entries and assembles each into a {@code ResultEntry} carrying a title,
 * Canonical_URL, and Snippet. Invalid page requests and requests beyond the last results are
 * reported via the {@code SearchResultsPage} status (Req 11.5, 11.6), and missing titles/snippets
 * are substituted with the Canonical_URL and an empty string respectively (Req 11.7).
 */
package com.searchengine.serving;
