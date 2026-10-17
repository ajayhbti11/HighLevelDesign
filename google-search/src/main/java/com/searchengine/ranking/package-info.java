/**
 * Ranker subsystem (design §6, §G "Ranking"; Req 10).
 *
 * <p>Scores candidate Documents and orders them into a deterministic, strict total order
 * ({@code score} descending, {@code Doc_ID} ascending). The Relevance_Score blends a
 * term-relevance component ({@code bm25Norm}) and a document-authority component
 * ({@code authorityNorm}), each in {@code [0,1]}, via configurable weights that sum to 1,
 * clamped to {@code [0,1]}.
 */
package com.searchengine.ranking;
