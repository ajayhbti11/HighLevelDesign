# Implementation Plan: Web-Scale Search Engine (google-search)

## Overview

This plan implements the search engine in **Java**, using **jqwik** for property-based testing
(a minimum of 100 iterations per property) and **JUnit 5** for unit/example tests. It follows the
component decomposition in `design.md` and is ordered so that pure, deterministic core functions
(shared normalization, URL canonicalization, posting-list construction, phrase matching, ranking
order, pagination) are built and verified first, before the I/O-bound components (Robots Manager,
Fetcher, Re-fetch Scheduler, control plane) that depend on them.

Each of the 47 Correctness Properties in the design is implemented by a single property-based test,
placed close to the code it validates so errors are caught early. PBT sub-tasks are tagged in the
format `Feature: google-search, Property N: ...` and reference the requirements clauses they check.

Test sub-tasks are marked optional with `*`. Core implementation tasks are never optional.

## Tasks

- [x] 1. Set up project structure, build tooling, and core types
  - Create a Maven/Gradle Java project with source and test source roots
  - Add JUnit 5 and jqwik (net.jqwik) test dependencies; configure the test runner to allow tag comments
  - Define shared value types and enums used across components: `DocId`, `ShardId`, `Token`,
    `AddResult`, `FetchOutcome`/`FailReason`, `PageStatus`, and result/record carriers
    (`ContentRecord`, `ParsedDocument`, `Posting`, `RankedResult`, `ResultEntry`, `SearchResultsPage`)
  - Establish package layout: `crawler`, `parser`, `index`, `query`, `retrieval`, `ranking`,
    `serving`, `controlplane`, `observability`, `common`
  - _Requirements: 5.3, 5.7, 3.1, 4.4, 11.2_

- [x] 2. Implement shared normalization (single source of truth)
  - [x] 2.1 Implement the pure `normalize(String)` -> `List<Token>` procedure
    - Apply Unicode NFKC, case-fold, split on Unicode whitespace/punctuation boundaries, drop empty
      tokens, collapse repeated whitespace; must be a pure deterministic function
    - This same function is later consumed by both Parser and Query_Processor
    - _Requirements: 4.3, 7.1_

  - [ ]* 2.2 Write property test for shared deterministic normalization
    - **Property 14: Shared deterministic normalization** — `normalize(t) == normalize(t)` and Parser
      and Query_Processor produce identical token sequences for the same input
    - **Validates: Requirements 4.3, 7.1**
    - Generators must include non-ASCII/Unicode text, empty content, and whitespace-only input
    - Min 100 iterations; tag `Feature: google-search, Property 14: Shared deterministic normalization`

- [x] 3. Implement URL canonicalization (pure)
  - [x] 3.1 Implement `canonicalize(url)` producing a Canonical_URL or INVALID
    - Validate absolute URL; lowercase scheme/host, punycode host, strip trailing dot, drop default
      port, remove dot-segments, percent-decode unreserved, collapse `//`, default empty path to `/`,
      drop tracking params, sort query params, drop fragment; deterministic total function
    - _Requirements: 1.2, 1.4, 1.6, 4.6_

  - [ ]* 3.2 Write unit tests for canonicalization edge cases
    - Case/default-port/fragment/param-order variants map to the same Canonical_URL; malformed and
      relative URLs return INVALID
    - _Requirements: 1.2, 1.6_

- [x] 4. Implement Frontier and SeenSet data structures
  - [x] 4.1 Implement `SeenSet` (bloom filter + exact hash set) with Content_Store membership hook
    - Fast-negative bloom plus exact set to avoid false-positive drops; `contains(canonicalUrl)`
    - _Requirements: 1.3, 1.6_

  - [x] 4.2 Implement `Frontier` over a priority queue keyed `(-priority, canonicalUrl)`
    - `add` (validates + dedups via canonicalize + SeenSet, returns AddResult), `next` (pops highest
      priority, ascending Canonical_URL tie-break, removes entry), `contains`, `reschedule`, `size`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [ ]* 4.3 Write property test for Frontier membership and canonical dedup
    - **Property 1: Frontier membership and canonical dedup** — after adding valid/invalid/equivalent
      URLs, Frontier holds exactly one entry per distinct valid Canonical_URL, no invalid URLs, and
      re-adding present/fetched URLs never increases count
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.6**
    - Generators must include canonical-equivalent variants and malformed URLs; min 100 iterations
    - Tag `Feature: google-search, Property 1: Frontier membership and canonical dedup`

  - [ ]* 4.4 Write property test for deterministic Frontier ordering
    - **Property 2: Deterministic Frontier ordering** — draining via `next()` yields entries in
      `(priority desc, Canonical_URL asc)` order, identical for identical input sets
    - **Validates: Requirements 1.4**
    - Min 100 iterations; tag `Feature: google-search, Property 2: Deterministic Frontier ordering`

  - [ ]* 4.5 Write property test for selection removal from Frontier
    - **Property 3: Selection removes from Frontier** — `next()` returns an entry, decreases size by
      exactly one, and the returned Canonical_URL is no longer contained
    - **Validates: Requirements 1.5**
    - Min 100 iterations; tag `Feature: google-search, Property 3: Selection removes from Frontier`

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement inverted index data structures and construction (pure core)
  - [x] 6.1 Implement `Posting`/`Posting_List` and per-Doc_ID segment builder
    - `buildSegment(doc)` maps term -> ascending positions; posting lists keep ascending Doc_IDs and
      ascending positions; delta/varint encoding for storage
    - _Requirements: 5.1, 5.2_

  - [ ]* 6.2 Write property test for postings and positions population
    - **Property 17: Indexing populates postings and positions** — for every distinct term, the
      posting list includes the Doc_ID with that term's exact ascending positions
    - **Validates: Requirements 5.1, 5.2**
    - Index/candidate generator with positions; min 100 iterations
    - Tag `Feature: google-search, Property 17: Indexing populates postings and positions`

  - [x] 6.3 Implement monotonic Doc_ID allocator and consistent-hash shard map
    - `shardFor(docId) = hash(docId) mod shardCount`; unique monotonic Doc_ID allocation
    - _Requirements: 5.3, 5.7_

  - [ ]* 6.4 Write property test for Doc_ID uniqueness
    - **Property 18: Doc_ID uniqueness** — Doc_IDs assigned to first-time indexed documents are all
      distinct across the whole index
    - **Validates: Requirements 5.3**
    - Min 100 iterations; tag `Feature: google-search, Property 18: Doc_ID uniqueness`

  - [ ]* 6.5 Write property test for single-shard membership
    - **Property 22: Each Doc_ID lives in exactly one Shard** — `shardFor` is deterministic and each
      Doc_ID belongs to exactly one shard's primary set
    - **Validates: Requirements 5.7**
    - Min 100 iterations; tag `Feature: google-search, Property 22: Each Doc_ID lives in exactly one Shard`

- [x] 7. Implement phrase / positional matching (pure)
  - [x] 7.1 Implement `phraseMatches(doc, phraseTokens)` consecutive-position check
    - Returns true iff all phrase tokens appear at consecutive positions with no gaps; any missing
      token -> false
    - _Requirements: 8.2_

  - [ ]* 7.2 Write property test for phrase adjacency membership
    - **Property 30: Phrase adjacency candidate membership** — a document matches a phrase iff it
      contains every token in order at consecutive positions with no intervening tokens
    - **Validates: Requirements 8.2**
    - Min 100 iterations; tag `Feature: google-search, Property 30: Phrase adjacency candidate membership`

- [x] 8. Implement ranking order and scoring (pure core)
  - [x] 8.1 Implement `Ranker.rank` scoring + total-order sort
    - `score = clamp01(w_r*bm25Norm + w_a*authorityNorm)` with `w_r + w_a = 1`; both components in
      `[0,1]`; sort by `(score desc, docId asc)`; empty candidates -> empty page; on per-doc scoring
      exception, skip doc + record failure + preserve remaining order
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_

  - [ ]* 8.2 Write property test for score bounds
    - **Property 32: Relevance_Score bounded to [0,1]**
    - **Validates: Requirements 10.1**
    - Score/authority generator; min 100 iterations
    - Tag `Feature: google-search, Property 32: Relevance_Score bounded to [0,1]`

  - [ ]* 8.3 Write property test for total ranking order
    - **Property 33: Total ranking order (score then Doc_ID)** — ordered by descending score, ties
      broken by ascending Doc_ID, yielding a strict total order
    - **Validates: Requirements 10.2, 10.3**
    - Generator forces score ties; min 100 iterations
    - Tag `Feature: google-search, Property 33: Total ranking order (score then Doc_ID)`

  - [ ]* 8.4 Write property test for monotonic component contribution
    - **Property 34: Score responds monotonically to both components** — increasing relevance (fixed
      authority) or authority (fixed relevance) does not decrease the score
    - **Validates: Requirements 10.4**
    - Min 100 iterations; tag `Feature: google-search, Property 34: Score responds monotonically to both components`

  - [ ]* 8.5 Write property test for deterministic ranking
    - **Property 35: Deterministic ranking** — ranking the same candidate set twice yields identical
      ordered pages
    - **Validates: Requirements 10.5**
    - Min 100 iterations; tag `Feature: google-search, Property 35: Deterministic ranking`

  - [ ]* 8.6 Write property test for isolated scoring-failure exclusion
    - **Property 36: Scoring failure excludes only the failed document** — failed docs excluded,
      remaining order preserved, each failure recorded
    - **Validates: Requirements 10.7**
    - Generator injects scoring failures; min 100 iterations
    - Tag `Feature: google-search, Property 36: Scoring failure excludes only the failed document`

  - [ ]* 8.7 Write unit test for empty candidate ranking
    - Empty candidate set produces an empty ordered page without error
    - _Requirements: 10.6_

- [x] 9. Implement pagination and result assembly (pure)
  - [x] 9.1 Implement `paginate(ranked, page, pageSize)` and Result_Entry assembly
    - Invalid page (<1 or non-integer) -> `invalidPage`; start beyond end -> `noFurtherResults`;
      otherwise contiguous disjoint slice of <= pageSize; assemble title/Canonical_URL/snippet with
      substitution (missing title -> Canonical_URL, missing snippet -> empty string)
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

  - [ ]* 9.2 Write property test for bounded, ordered, disjoint pagination
    - **Property 38: Pagination is bounded, ordered, and disjoint** — each page has <= pageSize
      entries in ranker order; all pages are disjoint slices whose concatenation equals the full
      ranked list with no repeats
    - **Validates: Requirements 11.1, 11.3, 11.4**
    - Min 100 iterations; tag `Feature: google-search, Property 38: Pagination is bounded, ordered, and disjoint`

  - [ ]* 9.3 Write property test for result entry completeness with substitution
    - **Property 37: Result entries are complete with substitution** — every entry has title,
      Canonical_URL, snippet; missing title -> Canonical_URL, missing snippet -> empty string
    - **Validates: Requirements 11.2, 11.7**
    - Generator includes entries missing title and/or snippet; min 100 iterations
    - Tag `Feature: google-search, Property 37: Result entries are complete with substitution`

  - [ ]* 9.4 Write unit tests for pagination boundaries
    - Page beyond last -> `noFurtherResults`; invalid/non-integer page -> `invalidPage`
    - _Requirements: 11.5, 11.6_

- [ ] 10. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Implement Query_Processor
  - [x] 11.1 Implement `parse`, `serialize`, `isEmpty` over the QueryNode tree
    - Truncate normalized length to 2048 first; share `normalize()`; preserve quoted phrases as
      ordered phrase nodes; interpret supported operators per grammar; unbalanced quote -> literal
      token; unsupported operator -> literal token; empty-after-normalization detection
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8_

  - [ ]* 11.2 Write property test for query normalization invariance
    - **Property 24: Query normalization invariance** — variants differing only in case or
      leading/trailing/repeated internal whitespace parse to identical StructuredQuery
    - **Validates: Requirements 7.1**
    - Query generator with case/whitespace variants; min 100 iterations
    - Tag `Feature: google-search, Property 24: Query normalization invariance`

  - [ ]* 11.3 Write property test for quoted-phrase preservation
    - **Property 25: Quoted phrases preserved as ordered constraints**
    - **Validates: Requirements 7.2**
    - Min 100 iterations; tag `Feature: google-search, Property 25: Quoted phrases preserved as ordered constraints`

  - [ ]* 11.4 Write property test for supported-operator interpretation
    - **Property 26: Supported operators interpreted per grammar**
    - **Validates: Requirements 7.3**
    - Min 100 iterations; tag `Feature: google-search, Property 26: Supported operators interpreted per grammar`

  - [ ]* 11.5 Write property test for parse/serialize round-trip
    - **Property 27: Query parse/serialize round-trip** — parse -> serialize -> parse yields an
      equivalent StructuredQuery (same tokens, phrases, operators in order)
    - **Validates: Requirements 7.5**
    - Min 100 iterations; tag `Feature: google-search, Property 27: Query parse/serialize round-trip`

  - [ ]* 11.6 Write property test for robust malformed-input parsing
    - **Property 28: Robust parsing of malformed operators and quotes** — unbalanced quote or
      unsupported operator becomes a literal token, parsing succeeds, remaining tokens preserved
    - **Validates: Requirements 7.6, 7.7**
    - Query generator with unbalanced quotes and unknown operators; min 100 iterations
    - Tag `Feature: google-search, Property 28: Robust parsing of malformed operators and quotes`

  - [ ]* 11.7 Write unit tests for empty query and truncation
    - Empty/whitespace-only query -> `noQueryTerms`; query normalized length > 2048 truncated first
    - _Requirements: 7.4, 7.8_

- [x] 12. Implement Parser subsystem
  - [x] 12.1 Implement `Parser.parse` (text/links/title extraction + normalization + hashing)
    - Extract human-readable text excluding markup/scripts/styles; extract outbound links and title;
      normalize via shared `normalize()`; compute contentHash; set parse-failure status for
      unsupported content type or too-large documents (skip indexing)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

  - [x] 12.2 Implement `resolveDuplicate` (content-hash index -> smallest Canonical_URL)
    - Register contentHash; collapse duplicates to lexicographically smallest Canonical_URL
    - _Requirements: 4.6_

  - [ ]* 12.3 Write property test for parser extraction without markup
    - **Property 13: Parser extracts text, links, and title without markup** — normalized text
      contains no markup/script/style; extracted links and title equal those in the document
    - **Validates: Requirements 4.1, 4.2**
    - HTML document generator with tags, scripts, styles, links, titles; min 100 iterations
    - Tag `Feature: google-search, Property 13: Parser extracts text, links, and title without markup`

  - [ ]* 12.4 Write property test for unsupported-content-type exclusion
    - **Property 15: Unsupported content type is not indexed** — parse-failure recorded; document not
      added to the Inverted_Index
    - **Validates: Requirements 4.4**
    - Generator includes non-HTML content types; min 100 iterations
    - Tag `Feature: google-search, Property 15: Unsupported content type is not indexed`

  - [ ]* 12.5 Write property test for duplicate collapse to smallest Canonical_URL
    - **Property 16: Duplicate content collapses to smallest Canonical_URL**
    - **Validates: Requirements 4.6**
    - Generator produces documents with identical normalized content and varied URLs; min 100 iterations
    - Tag `Feature: google-search, Property 16: Duplicate content collapses to smallest Canonical_URL`

  - [ ]* 12.6 Write unit test for too-large document boundary
    - Document at/over configured max parseable size records parse-failure and skips indexing
    - _Requirements: 4.5_

- [x] 13. Implement Indexer subsystem (build / atomic replace / delete)
  - [x] 13.1 Implement `index`, `reindex` (atomic pointer swap), `remove`
    - `index` assigns unique Doc_ID and populates postings/positions; `reindex` builds a new
      per-Doc_ID segment and swaps a pointer under a per-shard lock so no query sees mixed state;
      `remove` deletes all postings referencing the Doc_ID; failure discards the new segment and
      reverts, recording the failure
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 6.2_

  - [ ]* 13.2 Write property test for atomic re-index
    - **Property 19: Atomic re-index (no mixed state)** — every observable snapshot exposes only the
      prior or only the current postings for a Doc_ID, never a mixture
    - **Validates: Requirements 5.4**
    - Min 100 iterations; tag `Feature: google-search, Property 19: Atomic re-index (no mixed state)`

  - [ ]* 13.3 Write property test for full posting removal
    - **Property 20: Removal deletes all postings for a Doc_ID** — after removal (including re-fetch
      404/410), no posting list references the Doc_ID and it is a candidate for no query
    - **Validates: Requirements 5.5, 6.2**
    - Min 100 iterations; tag `Feature: google-search, Property 20: Removal deletes all postings for a Doc_ID`

  - [ ]* 13.4 Write property test for indexing-failure revert
    - **Property 21: Indexing failure reverts to prior state** — after a failure post partial update,
      the index equals its prior state and the failure is recorded
    - **Validates: Requirements 5.6**
    - Generator injects mid-update failures; min 100 iterations
    - Tag `Feature: google-search, Property 21: Indexing failure reverts to prior state`

- [x] 14. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 15. Implement Candidate_Retrieval and shard merge
  - [ ] 15.1 Implement `retrieve` (AND intersect + phrase filter + shard union merge)
    - Intersect required-term posting lists by Doc_ID (AND semantics); apply phrase filter; union
      per-shard candidates retaining one entry per Doc_ID; no-match -> empty page status `noMatches`;
      carry partial/missingShards flags for later serving integration
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [ ]* 15.2 Write property test for AND candidate membership
    - **Property 29: Required-term (AND) candidate membership** — a document is a candidate iff it
      satisfies every required term constraint
    - **Validates: Requirements 8.1**
    - Index/candidate generator with controlled overlaps; min 100 iterations
    - Tag `Feature: google-search, Property 29: Required-term (AND) candidate membership`

  - [ ]* 15.3 Write property test for shard union with one entry per Doc_ID
    - **Property 31: Shard union with one entry per Doc_ID** — merged set equals the union of
      per-shard sets with exactly one entry per Doc_ID
    - **Validates: Requirements 8.3**
    - Generator produces overlapping per-shard candidate sets; min 100 iterations
    - Tag `Feature: google-search, Property 31: Shard union with one entry per Doc_ID`

  - [ ]* 15.4 Write unit test for no-match retrieval
    - Query with no satisfying document returns empty page with `noMatches` status
    - _Requirements: 8.4_

- [x] 16. Implement Robots Manager
  - [x] 16.1 Implement `ensurePolicy`, `isAllowed`, `effectiveDelaySeconds`
    - Fetch/record policy before content fetch; refresh when older than refresh interval; longest-match
      allow/disallow decision; effective delay `clamp(max(minDelay, crawlDelay), 0.1, 60)`; treat
      fetch failure/malformed/not-found as no restrictions using min delay, recording the condition
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [ ]* 16.2 Write property test for effective delay computation
    - **Property 4: Robots effective delay computation** — effective delay equals
      `clamp(max(minDelay, crawlDelay), 0.1, 60)` and is never below the configured minimum
    - **Validates: Requirements 2.5, 2.6**
    - Min 100 iterations; tag `Feature: google-search, Property 4: Robots effective delay computation`

  - [ ]* 16.3 Write property test for missing/unusable policy allows all
    - **Property 5: Missing or unusable robots policy allows all** — on fetch failure/malformed/
      not-found, `isAllowed` is true and effective delay is the min delay, condition recorded
    - **Validates: Requirements 2.2, 2.3**
    - Min 100 iterations; tag `Feature: google-search, Property 5: Missing or unusable robots policy allows all`

  - [ ]* 16.4 Write property test for allow/disallow decision
    - **Property 6: Robots allow/disallow decision** — `isAllowed` is false exactly when the path's
      longest-matching rule is a disallow rule, true otherwise
    - **Validates: Requirements 2.4**
    - Min 100 iterations; tag `Feature: google-search, Property 6: Robots allow/disallow decision`

  - [ ]* 16.5 Write property test for refresh on staleness
    - **Property 7: Robots refresh on staleness** — `ensurePolicy` re-fetches iff policy age exceeds
      the refresh interval
    - **Validates: Requirements 2.7**
    - Min 100 iterations; tag `Feature: google-search, Property 7: Robots refresh on staleness`

  - [ ]* 16.6 Write unit test for robots-before-content ordering
    - First scheduling of a URL for a host fetches/records the policy before any content fetch
    - _Requirements: 2.1_

- [ ] 17. Implement Fetcher and Content_Store record handling
  - [ ] 17.1 Implement `fetch` and Crawler outcome interpretation
    - Apply timeout in [1,120]s (default 30); honor robots delay; store complete ContentRecord on 200;
      5xx/timeout -> Retry (retryCount+1); max retries -> FailedPermanent(maxRetries); 3xx -> enqueue
      target + record edge, cap redirect chain (FailedPermanent(redirectLimit)); 4xx ->
      FailedPermanent(clientError4xx) no reschedule; record timestamp + status for each attempt
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [ ]* 17.2 Write property test for successful-fetch record completeness
    - **Property 8: Successful fetch persists complete record** — stored ContentRecord has raw body,
      timestamp, status, final URL, content type equal to the response
    - **Validates: Requirements 3.1, 3.7**
    - Min 100 iterations; tag `Feature: google-search, Property 8: Successful fetch persists complete record`

  - [ ]* 17.3 Write property test for transient-failure reschedule
    - **Property 9: Transient failure reschedules with incremented retry** — 5xx/timeout reschedules
      with retryCount incremented by exactly 1
    - **Validates: Requirements 3.2**
    - Min 100 iterations; tag `Feature: google-search, Property 9: Transient failure reschedules with incremented retry`

  - [ ]* 17.4 Write property test for retry/redirect limit failure
    - **Property 10: Retry and redirect limits mark URL failed** — reaching max retries or exceeding
      max redirects marks URL failed (maxRetries/redirectLimit) and excludes from scheduling
    - **Validates: Requirements 3.3, 3.5**
    - Min 100 iterations; tag `Feature: google-search, Property 10: Retry and redirect limits mark URL failed`

  - [ ]* 17.5 Write property test for redirect enqueue and edge recording
    - **Property 11: Redirect enqueues target and records edge**
    - **Validates: Requirements 3.4**
    - Min 100 iterations; tag `Feature: google-search, Property 11: Redirect enqueues target and records edge`

  - [ ]* 17.6 Write property test for client-error failure without reschedule
    - **Property 12: Client error fails without reschedule** — 4xx marks URL failed, no reschedule,
      records timestamp + status
    - **Validates: Requirements 3.6**
    - Min 100 iterations; tag `Feature: google-search, Property 12: Client error fails without reschedule`

  - [ ]* 17.7 Write unit tests for retry and redirect boundaries
    - Max-retry boundary (default 3) and redirect-limit boundary (default 5)
    - _Requirements: 3.3, 3.5_

- [ ] 18. Implement Re-fetch Scheduler
  - [ ] 18.1 Implement `nextInterval` and `schedule`
    - Decrease interval on detected change, increase on no change, clamp to
      `[minInterval >= 1 min, maxInterval <= 30 days]`; schedule re-fetch into the Frontier
    - _Requirements: 6.3_

  - [ ]* 18.2 Write property test for adaptive interval direction and bounds
    - **Property 23: Adaptive re-fetch interval direction and bounds** — interval decreases after
      change, increases after no-change, always within configured bounds
    - **Validates: Requirements 6.3**
    - Generator produces change/no-change sequences; min 100 iterations
    - Tag `Feature: google-search, Property 23: Adaptive re-fetch interval direction and bounds`

- [x] 19. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 20. Implement sharding/replication control plane
  - [x] 20.1 Implement replica selection and shard scale-out/rebalancing
    - Health-tracked replica set per shard; `pickHealthyReplica`; add shards when docs-per-shard
      exceeds max; consistent-hash reassignment keeping each Doc_ID in exactly one shard with no loss
      or duplication; record capacity-exhaustion when no capacity can be provisioned
    - _Requirements: 13.2, 13.3, 13.4, 14.1, 14.3, 14.4_

  - [ ]* 20.2 Write property test for replica failover selection
    - **Property 41: Replica failover selects a healthy replica** — retrieval routes to a healthy
      replica whenever a shard has at least one healthy replica
    - **Validates: Requirements 13.2**
    - Health-state generator; min 100 iterations
    - Tag `Feature: google-search, Property 41: Replica failover selects a healthy replica`

  - [ ]* 20.3 Write property test for coverage completeness vs partial
    - **Property 42: Coverage determines completeness vs partial** — full coverage -> complete page;
      any shard with zero healthy replicas -> partial with incomplete-shard-coverage status
    - **Validates: Requirements 13.3, 13.4**
    - Health-state generator with shard-loss cases; min 100 iterations
    - Tag `Feature: google-search, Property 42: Coverage determines completeness vs partial`

  - [ ]* 20.4 Write property test for shard scale-out bound
    - **Property 43: Shard scale-out keeps docs-per-shard within bound** — after adding shards, every
      shard holds at or below the configured maximum
    - **Validates: Requirements 14.1**
    - Assignment generator; min 100 iterations
    - Tag `Feature: google-search, Property 43: Shard scale-out keeps docs-per-shard within bound`

  - [ ]* 20.5 Write property test for rebalancing conservation
    - **Property 44: Rebalancing conserves Doc_IDs** — after adding a shard and reassigning, the
      Doc_ID set is unchanged and each is assigned to exactly one shard (no loss/duplication)
    - **Validates: Requirements 14.3**
    - Assignment generator; min 100 iterations
    - Tag `Feature: google-search, Property 44: Rebalancing conserves Doc_IDs`

  - [ ]* 20.6 Write unit test for capacity-exhaustion recording
    - When no shard/replica capacity can be provisioned, a capacity-exhaustion condition is recorded
    - _Requirements: 14.4_

- [x] 21. Implement Observability (metrics, error records, admission counters)
  - [x] 21.1 Implement metric/error recording with non-aborting failure handling
    - Record per-query latency + result count; record crawl-cycle and index-cycle counts; record
      error records (component, operation, cause, timestamp); if recording fails, complete the
      primary operation and record a recording-failure indication
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.6_

  - [ ]* 21.2 Write property test for cycle-count consistency
    - **Property 45: Crawl and indexing cycle counts are consistent** — fetch attempts = successes +
      failures; index added/updated/removed equal operations performed
    - **Validates: Requirements 15.2, 15.3**
    - Min 100 iterations; tag `Feature: google-search, Property 45: Crawl and indexing cycle counts are consistent`

  - [ ]* 21.3 Write property test for per-query metric and error record completeness
    - **Property 46: Per-query metric and error records are complete** — served query records latency
      + result count; each failure records component/operation/cause/timestamp
    - **Validates: Requirements 15.1, 15.4**
    - Min 100 iterations; tag `Feature: google-search, Property 46: Per-query metric and error records are complete`

  - [ ]* 21.4 Write property test for non-aborting recording failure
    - **Property 47: Recording failure never aborts the primary operation** — on recording failure the
      primary operation still completes and a recording-failure indication is recorded
    - **Validates: Requirements 15.6**
    - Generator injects recording failures; min 100 iterations
    - Tag `Feature: google-search, Property 47: Recording failure never aborts the primary operation`

- [ ] 22. Implement Serving Layer and wire the serving plane together
  - [-] 22.1 Implement `ServingLayer.search` orchestration + admission control
    - Wire admission/rate-limit (reject excess -> `overloaded`) -> Query_Processor.parse ->
      Candidate_Retrieval (per-shard 200 ms deadline via control plane + failover) -> Ranker ->
      paginate/assemble -> emit metrics; map statuses (noQueryTerms, noMatches, noFurtherResults,
      invalidPage, overloaded, partialResult, incompleteShardCoverage) with partialShardCount
    - _Requirements: 7.4, 8.4, 10.6, 11.5, 11.6, 12.3, 12.4, 13.4, 15.1_

  - [ ]* 22.2 Write property test for admission control cap
    - **Property 39: Admission control caps accepted rate** — accepted queries per window never exceed
      the configured peak rate; rejected queries get `overloaded`; accepted queries unaffected
    - **Validates: Requirements 12.3**
    - Generator produces arrival patterns above/below peak; min 100 iterations
    - Tag `Feature: google-search, Property 39: Admission control caps accepted rate`

  - [ ]* 22.3 Write property test for partial results on shard timeout
    - **Property 40: Partial results on shard timeout** — timed-out shards produce a page from
      responders with partial status and a non-responding-shard count equal to timed-out shards
    - **Validates: Requirements 12.4**
    - Health/timeout generator; min 100 iterations
    - Tag `Feature: google-search, Property 40: Partial results on shard timeout`

- [ ] 23. Wire the indexing plane together
  - [-] 23.1 Connect Crawler pipeline end to end in code
    - Wire Frontier -> RobotsManager -> Fetcher -> Content_Store -> Parser -> Indexer -> Inverted_Index
      and feed Parser-discovered links + Re-fetch Scheduler back into the Frontier; connect Indexer to
      the control-plane shard map and Observability cycle counters
    - _Requirements: 1.3, 3.1, 4.1, 5.1, 5.7, 6.2, 6.3, 15.2, 15.3_

  - [ ]* 23.2 Write integration test for crawl -> parse -> index -> retrieve flow
    - Using in-memory fakes for HTTP/storage/replicas, verify a fetched document becomes retrievable
      and a 404/410 re-fetch removes it; verify end-to-end retrieval against the built index
    - _Requirements: 3.1, 4.1, 5.1, 6.2, 8.1_

- [ ] 24. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Implemented in **Java** with **jqwik** (property-based tests, min 100 iterations each) and
  **JUnit 5** (unit/example tests).
- Tasks marked with `*` are optional test tasks and can be skipped for a faster MVP.
- Each of the 47 Correctness Properties is implemented by exactly one property-based test, tagged
  `Feature: google-search, Property N: ...` and annotated with the requirements clause it validates.
- Pure, deterministic core functions (normalize, canonicalize, posting/segment construction, phrase
  matching, ranking order, pagination) are built and tested before I/O-bound components so errors
  surface early and dependencies flow one way.
- Infrastructural/SLO criteria (6.1, 12.1, 12.2, 13.1, 13.5, 14.2, 15.5) are validated by
  integration/load/smoke tests outside the property-based suite, consistent with the design's testing
  strategy; the integration test in 23.2 covers pipeline freshness and end-to-end retrieval.
- Checkpoints ensure incremental validation at natural boundaries.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "6.1", "7.1", "8.1", "9.1"] },
    { "id": 2, "tasks": ["2.2", "3.2", "6.2", "6.3", "7.2", "8.2", "8.3", "8.4", "8.5", "8.6", "8.7", "9.2", "9.3", "9.4"] },
    { "id": 3, "tasks": ["4.1", "6.4", "6.5", "11.1", "12.1"] },
    { "id": 4, "tasks": ["4.2", "11.2", "11.3", "11.4", "11.5", "11.6", "11.7", "12.2", "12.3", "12.4", "12.5", "12.6"] },
    { "id": 5, "tasks": ["4.3", "4.4", "4.5", "13.1", "15.1"] },
    { "id": 6, "tasks": ["13.2", "13.3", "13.4", "15.2", "15.3", "15.4", "16.1", "18.1", "20.1", "21.1"] },
    { "id": 7, "tasks": ["16.2", "16.3", "16.4", "16.5", "16.6", "17.1", "18.2", "20.2", "20.3", "20.4", "20.5", "20.6", "21.2", "21.3", "21.4"] },
    { "id": 8, "tasks": ["17.2", "17.3", "17.4", "17.5", "17.6", "17.7", "22.1"] },
    { "id": 9, "tasks": ["22.2", "22.3", "23.1"] },
    { "id": 10, "tasks": ["23.2"] }
  ]
}
```
