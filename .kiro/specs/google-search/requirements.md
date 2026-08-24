# Requirements Document

## Introduction

This document specifies the requirements for a web-scale search engine (referred to as "Search Engine") comparable to Google Search. The system discovers content across the public web, builds and maintains a searchable index, interprets user queries, ranks matching documents by relevance, and returns results at low latency and high throughput.

The requirements span the complete search pipeline:

1. **Web Crawling** - discovering and fetching web content
2. **Content Processing and Indexing** - parsing, normalizing, and building inverted indexes
3. **Query Processing** - parsing, normalizing, and interpreting user queries
4. **Ranking** - scoring and ordering candidate documents by relevance
5. **Serving** - returning results to end users at scale
6. **Cross-cutting concerns** - availability, scalability, freshness, and observability

The design phase will elaborate BOTH a high-level design (system architecture, major components, data models, partitioning, and scaling strategy) AND a low-level design (core algorithms, data structures, and key class/function signatures for each component). This requirements document defines the observable behavior each of those components must satisfy.

## Glossary

- **Search Engine**: The complete system that crawls, indexes, and serves search results.
- **Crawler**: The subsystem that discovers URLs and fetches web page content.
- **Frontier**: The prioritized queue of URLs scheduled to be crawled.
- **Fetcher**: The Crawler component that performs the actual HTTP retrieval of a URL.
- **Robots_Policy**: The set of crawling rules published by a host via its robots.txt file.
- **Content_Store**: The durable repository holding raw and parsed fetched documents.
- **Parser**: The component that extracts text, links, and metadata from a raw fetched document.
- **Indexer**: The subsystem that transforms parsed documents into index structures.
- **Inverted_Index**: The data structure mapping each term to the list of documents (posting list) containing that term.
- **Posting_List**: The ordered list of document identifiers and positional data for a single term.
- **Document**: A single crawled and indexed web resource identified by a canonical URL.
- **Doc_ID**: The internal unique identifier assigned to a Document.
- **Query_Processor**: The subsystem that parses and normalizes an incoming user query.
- **Query**: The text and parameters submitted by a User to retrieve results.
- **Ranker**: The subsystem that scores and orders candidate Documents for a Query.
- **Relevance_Score**: The numeric value the Ranker assigns to a candidate Document for a given Query.
- **Serving_Layer**: The subsystem that receives user Queries and returns ranked results.
- **Search_Results_Page**: The ordered set of result entries returned for a Query.
- **User**: An end user submitting a Query to the Search Engine.
- **Result_Entry**: A single item on the Search_Results_Page containing title, URL, and snippet.
- **Snippet**: The short text excerpt shown for a Result_Entry summarizing document relevance.
- **Freshness_Interval**: The elapsed time between a Document being published or changed and being reflected in the Inverted_Index.
- **Canonical_URL**: The single normalized URL chosen to represent a set of duplicate or equivalent URLs.
- **Shard**: A horizontal partition of the Inverted_Index holding a subset of Documents.
- **Query_Latency**: The elapsed time between the Serving_Layer receiving a Query and returning the Search_Results_Page.

## Requirements

### Requirement 1: URL Discovery and Frontier Management

**User Story:** As a search operator, I want the Crawler to continuously discover and schedule URLs, so that the index reflects the reachable public web.

#### Acceptance Criteria

1. WHEN a seed URL set is provided, THE Crawler SHALL add each syntactically valid seed URL to the Frontier.
2. IF a seed URL or an extracted outbound link is not a syntactically valid absolute URL, THEN THE Crawler SHALL exclude the URL from the Frontier and record the invalid-URL condition.
3. WHEN the Parser extracts an outbound link from a fetched Document, THE Crawler SHALL add the link to the Frontier if the link is not already present in the Frontier or Content_Store.
4. THE Frontier SHALL order URLs for fetching by descending computed priority value, breaking ties by ascending Canonical_URL so that ordering is deterministic.
5. WHEN a URL is selected for fetching, THE Crawler SHALL remove the URL from the Frontier.
6. WHERE two or more URLs normalize to the same Canonical_URL, THE Crawler SHALL retain exactly one entry in the Frontier for that Canonical_URL.

### Requirement 2: Politeness and Robots Compliance

**User Story:** As a search operator, I want the Crawler to respect host crawling policies, so that the system remains a well-behaved web citizen and avoids overloading hosts.

#### Acceptance Criteria

1. WHEN the Crawler first schedules a URL for a host, THE Crawler SHALL fetch and record the host Robots_Policy before fetching any content URL for that host.
2. IF the Robots_Policy fetch for a host fails or returns a malformed response, THEN THE Crawler SHALL treat the host as having no crawl restrictions, record the fetch failure, and proceed using the configured minimum delay.
3. IF a host returns no Robots_Policy (for example, a not-found response), THEN THE Crawler SHALL treat all URLs for that host as allowed and proceed using the configured minimum delay.
4. IF a URL is disallowed by the host Robots_Policy, THEN THE Crawler SHALL exclude the URL from fetching and record the exclusion.
5. WHILE crawling a single host, THE Crawler SHALL maintain a minimum configured delay, in the range of 0.1 to 60 seconds, between consecutive requests to that host.
6. WHERE the Robots_Policy specifies a crawl-delay value, THE Crawler SHALL apply the greater of the configured minimum delay and the specified crawl-delay value.
7. WHEN a host Robots_Policy record is older than its configured refresh interval, THE Crawler SHALL re-fetch the Robots_Policy before fetching further URLs for that host.

### Requirement 3: Content Fetching and Error Handling

**User Story:** As a search operator, I want the Fetcher to retrieve content reliably and handle failures, so that transient errors do not permanently drop reachable content.

#### Acceptance Criteria

1. WHEN the Fetcher retrieves a URL and receives an HTTP 200 response, THE Fetcher SHALL store the raw response body, the fetch timestamp, the HTTP status code, the final resolved URL, and the response content type in the Content_Store.
2. IF the Fetcher receives an HTTP 5xx response or does not receive a complete response within a configured request timeout between 1 and 120 seconds (default 30 seconds), THEN THE Fetcher SHALL reschedule the URL in the Frontier with a retry count incremented by 1.
3. IF a URL reaches the configured maximum retry count between 1 and 10 attempts (default 3 attempts), THEN THE Fetcher SHALL mark the URL as failed and exclude the URL from all further scheduling.
4. WHEN the Fetcher receives an HTTP 3xx redirect response, THE Fetcher SHALL add the redirect target URL to the Frontier and record the source-to-target redirect relationship.
5. IF a redirect chain for a single originating URL exceeds a configured maximum of between 1 and 20 redirects (default 5 redirects), THEN THE Fetcher SHALL stop following the chain, mark the originating URL as failed with an indication that the redirect limit was exceeded, and exclude the originating URL from further scheduling.
6. IF the Fetcher receives an HTTP 4xx response, THEN THE Fetcher SHALL mark the URL as failed without rescheduling, exclude the URL from further scheduling, and record the fetch timestamp and HTTP status code in the Content_Store.
7. THE Fetcher SHALL record the fetch timestamp and HTTP status code for each fetch attempt in the Content_Store.

### Requirement 4: Document Parsing and Normalization

**User Story:** As a search operator, I want fetched documents parsed into structured text and links, so that content can be indexed and further URLs discovered.

#### Acceptance Criteria

1. WHEN a raw Document is available in the Content_Store, THE Parser SHALL extract the human-readable text content of the Document, excluding markup tags, scripts, and styling directives.
2. WHEN a raw Document is parsed, THE Parser SHALL extract all outbound links and the title metadata from the Document.
3. THE Parser SHALL normalize extracted text to a canonical form using the same defined tokenization and case-folding procedure applied by the Query_Processor, so that identical input text always produces identical normalized output.
4. IF a Document declares a content type that is not in the configured set of supported content types, THEN THE Parser SHALL record a parse-failure status and skip indexing for that Document.
5. IF a Document exceeds the configured maximum parseable size, THEN THE Parser SHALL record a parse-failure status and skip indexing for that Document.
6. WHEN two Documents produce identical normalized content, THE Parser SHALL mark the Documents as duplicates and associate them with a single Canonical_URL chosen as the lexicographically smallest Canonical_URL among the duplicate set.

### Requirement 5: Inverted Index Construction

**User Story:** As a search operator, I want parsed documents converted into an inverted index, so that queries can retrieve matching documents efficiently.

#### Acceptance Criteria

1. WHEN a parsed Document is indexed, THE Indexer SHALL add the Document Doc_ID to the Posting_List of each term the Document contains.
2. WHEN a parsed Document is indexed, THE Indexer SHALL store each term position of the term within that Document in the Posting_List.
3. WHEN a Document is indexed for the first time, THE Indexer SHALL assign the Document a Doc_ID that is unique across all Documents in the Inverted_Index.
4. WHEN a Document is re-indexed after its content changes, THE Indexer SHALL atomically replace the prior Posting_List entries for that Doc_ID with entries reflecting the current content, so that no Query observes a mixture of prior and current entries for that Doc_ID.
5. WHEN a Document is removed from the index, THE Indexer SHALL remove all Posting_List entries referencing that Doc_ID.
6. IF indexing a Document fails after partial Posting_List updates, THEN THE Indexer SHALL revert the partial updates so that the Inverted_Index reflects either the fully updated Document or the prior state, and SHALL record the indexing failure.
7. THE Indexer SHALL partition the Inverted_Index across Shards so that each Doc_ID is assigned to exactly one Shard.

### Requirement 6: Index Freshness

**User Story:** As a User, I want recently published or updated pages to appear in results, so that search reflects current information.

#### Acceptance Criteria

1. WHEN a Document is successfully fetched and parsed, THE Indexer SHALL make the Document retrievable through the Inverted_Index within the configured Freshness_Interval, where the Freshness_Interval is a configured value between 1 minute and 24 hours.
2. WHEN a previously indexed Document returns an HTTP 404 or 410 response on re-fetch, THE Indexer SHALL remove the Document from the Inverted_Index within the configured Freshness_Interval so that the removed Document is no longer returned as a candidate for any Query.
3. THE Crawler SHALL schedule re-fetching of each indexed Document at an interval that decreases when consecutive re-fetches detect changed normalized content and increases when consecutive re-fetches detect unchanged normalized content, bounded to a configured minimum re-fetch interval of no less than 1 minute and a configured maximum re-fetch interval of no more than 30 days.

### Requirement 7: Query Parsing and Normalization

**User Story:** As a User, I want my query text interpreted consistently, so that I receive relevant results regardless of minor formatting differences.

#### Acceptance Criteria

1. WHEN a User submits a Query, THE Query_Processor SHALL tokenize and case-fold the Query text using the same normalization procedure applied by the Parser, such that two Queries differing only in letter case or in leading, trailing, or repeated internal whitespace produce identical structured Query representations.
2. WHERE a Query contains quoted phrases, THE Query_Processor SHALL preserve the enclosed token sequence as an ordered phrase constraint.
3. WHERE a Query contains a supported operator, THE Query_Processor SHALL interpret the operator according to the defined operator grammar.
4. IF a Query is empty after normalization, THEN THE Serving_Layer SHALL return an empty Search_Results_Page with a status indicating no query terms.
5. THE Query_Processor SHALL parse a Query into a structured Query representation, and re-serializing that representation SHALL produce a Query that parses to an equivalent structured Query representation, where two structured representations are equivalent when they contain the same tokens, phrase constraints, and operators in the same order.
6. IF a Query contains an unbalanced quote character, THEN THE Query_Processor SHALL treat the unmatched quote as a literal token and continue parsing the remaining Query text without rejecting the Query.
7. IF a Query contains an operator that is not part of the defined operator grammar, THEN THE Query_Processor SHALL treat the unsupported operator as a literal token and continue parsing the remaining Query text.
8. WHEN a User submits a Query whose normalized length exceeds 2,048 characters, THE Query_Processor SHALL truncate the Query to the first 2,048 characters before tokenization.

### Requirement 8: Candidate Retrieval

**User Story:** As a User, I want the system to find documents matching my query, so that relevant pages become candidates for ranking.

#### Acceptance Criteria

1. WHEN a normalized Query containing one or more terms is processed, THE Serving_Layer SHALL retrieve from the Inverted_Index the set of Documents whose Posting_Lists collectively satisfy every required Query term constraint, such that a Document is included as a candidate if and only if it satisfies all required term constraints of the Query.
2. WHERE a Query specifies a phrase constraint, THE Serving_Layer SHALL include a Document as a candidate only if the Document contains every token of the phrase in the specified order at consecutive term positions with no intervening tokens.
3. WHEN candidate retrieval spans two or more Shards, THE Serving_Layer SHALL query every Shard and return the union of the returned candidate sets, retaining exactly one candidate entry per Doc_ID.
4. IF no Document satisfies the Query term constraints, THEN THE Serving_Layer SHALL return an empty Search_Results_Page with a status indicating no matches.

### Requirement 10: Ranking

**User Story:** As a User, I want the most relevant results shown first, so that I find what I need quickly.

#### Acceptance Criteria

1. WHEN candidate Documents are retrieved for a Query, THE Ranker SHALL assign each candidate Document a Relevance_Score in the range of 0.0 to 1.0 inclusive.
2. THE Ranker SHALL order the Search_Results_Page by descending Relevance_Score.
3. WHERE two candidate Documents have equal Relevance_Score, THE Ranker SHALL order those Documents by ascending unique Document identifier so that repeated identical Queries produce identical result ordering.
4. THE Ranker SHALL compute the Relevance_Score so that both a term-relevance component and a document-authority component contribute to the resulting value.
5. WHEN a Query is re-submitted with identical text against an unchanged index, THE Ranker SHALL produce an identical ordered Search_Results_Page (deterministic ranking property).
6. IF the candidate Document set for a Query is empty, THEN THE Ranker SHALL produce an empty ordered Search_Results_Page without raising an error.
7. IF computing the Relevance_Score for a candidate Document fails, THEN THE Ranker SHALL exclude that Document from the Search_Results_Page, record the scoring failure, and preserve the ordering of the remaining Documents.

### Requirement 11: Result Serving and Pagination

**User Story:** As a User, I want to receive a readable, paginated results page, so that I can browse matches efficiently.

#### Acceptance Criteria

1. WHEN ranking completes, THE Serving_Layer SHALL return a Search_Results_Page containing a list of Result_Entry items ordered by the ranking order produced by the Ranker.
2. THE Serving_Layer SHALL include a title, Canonical_URL, and Snippet in each Result_Entry.
3. THE Serving_Layer SHALL return at most the configured page size of Result_Entry items per Search_Results_Page, where the configured page size is between 1 and 100 (default 10).
4. WHEN a User requests a subsequent results page, THE Serving_Layer SHALL return the next ordered subset of Result_Entry items without repeating items already returned for prior pages of the same Query.
5. WHEN a User requests a results page beyond the last available results, THE Serving_Layer SHALL return an empty Search_Results_Page with a status indicating no further results.
6. IF a User requests a page number that is less than 1 or is not an integer, THEN THE Serving_Layer SHALL return an empty Search_Results_Page with a status indicating an invalid page request.
7. IF a Result_Entry has no available title or no available Snippet, THEN THE Serving_Layer SHALL substitute the Canonical_URL for the missing title and an empty Snippet for the missing Snippet.

### Requirement 12: Query Latency and Throughput

**User Story:** As a User, I want fast responses even under heavy load, so that search feels instantaneous.

#### Acceptance Criteria

1. WHEN the Serving_Layer receives a Query while the sustained query rate is at or below the configured peak query rate, THE Serving_Layer SHALL return the Search_Results_Page within the configured Query_Latency target of 500 milliseconds measured at the 99th percentile over a rolling 5-minute window.
2. WHILE the sustained query rate is at or below the configured peak query rate, THE Serving_Layer SHALL maintain the configured Query_Latency target of 500 milliseconds at the 99th percentile.
3. IF the sustained query rate exceeds the configured peak query rate, THEN THE Serving_Layer SHALL reject Queries above the configured peak query rate and return for each rejected Query a Search_Results_Page-level status indicating the service is overloaded, without altering the Query_Latency target applied to accepted Queries.
4. IF a candidate Shard does not respond within its configured timeout of 200 milliseconds, THEN THE Serving_Layer SHALL return the Search_Results_Page computed from the responding Shards, include a Search_Results_Page-level status indicating a partial result, and record the partial-result condition with the count of non-responding Shards.

### Requirement 13: Availability and Fault Tolerance

**User Story:** As a User, I want search to stay available during component failures, so that I can rely on the service.

#### Acceptance Criteria

1. THE Inverted_Index SHALL store each Shard on at least the configured replication factor of independent replicas, where the configured replication factor is at least 2.
2. WHEN a Shard replica fails its configured health check for a configured detection window of at most 5 seconds, THE Serving_Layer SHALL mark that replica unavailable and route candidate retrieval for that Shard to an available replica of the same Shard.
3. WHILE at least one replica of every Shard remains available, THE Serving_Layer SHALL continue to accept and answer Queries using the available replicas, returning a complete Search_Results_Page.
4. IF all replicas of any Shard are unavailable, THEN THE Serving_Layer SHALL return the Search_Results_Page computed from the responding Shards, record the partial-result condition, and include a status indicating incomplete Shard coverage.
5. THE Serving_Layer SHALL meet a configured availability target of at least 99.9 percent of received Queries answered with an HTTP 200 response within the configured Query_Latency target of 500 milliseconds at the 99th percentile, measured over a rolling 30-day window.

### Requirement 14: Scalability

**User Story:** As a search operator, I want the system to scale with corpus and traffic growth, so that performance targets hold as demand increases.

#### Acceptance Criteria

1. WHEN the number of Documents assigned to a Shard reaches the configured maximum Documents per Shard, THE Indexer SHALL support adding one or more Shards so that the number of Documents per Shard returns to at or below the configured maximum.
2. WHEN the sustained query rate reaches the configured serving-capacity threshold, THE Serving_Layer SHALL support adding serving replicas so that the configured Query_Latency target is maintained.
3. WHEN a new Shard is added, THE Indexer SHALL reassign Documents across Shards so that each Doc_ID remains assigned to exactly one Shard and no Doc_ID is lost or duplicated during reassignment.
4. IF no additional Shard or serving-replica capacity can be provisioned when a scaling threshold is reached, THEN THE Search Engine SHALL record a capacity-exhaustion condition.

### Requirement 15: Observability

**User Story:** As a search operator, I want operational metrics and logs, so that I can monitor health and diagnose issues.

#### Acceptance Criteria

1. WHEN the Serving_Layer returns a Search_Results_Page for a Query, THE Serving_Layer SHALL record a metric containing the measured Query_Latency in milliseconds and the returned Result_Entry count for that Query.
2. WHEN a crawl cycle completes, THE Crawler SHALL record the total fetch-attempt count, the successful fetch count, and the failed fetch count for that crawl cycle.
3. WHEN an indexing cycle completes, THE Indexer SHALL record the count of Documents added, the count of Documents updated, and the count of Documents removed for that indexing cycle.
4. WHEN a component operation fails, THE Search Engine SHALL record an error record containing the component name, the operation name, the failure cause, and the timestamp of the failure.
5. THE Search Engine SHALL retain each recorded metric and error record for at least a configured retention period and SHALL make the retained records queryable by component name and time range.
6. IF recording a metric or error record fails, THEN THE Search Engine SHALL complete the primary operation that triggered the recording and SHALL record a recording-failure indication without aborting that primary operation.
