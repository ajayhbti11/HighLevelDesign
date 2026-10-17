package com.searchengine.query;

import java.util.List;
import java.util.Objects;

/**
 * StructuredQuery: the parsed representation of a user query — an ordered list of
 * {@link QueryNode}s (design §5, Requirement 7).
 *
 * <p>Ordering is significant: two structured queries are considered <em>equivalent</em> (Req 7.5)
 * exactly when they contain the same tokens, phrase constraints, and operators in the same order.
 * Because this type and every {@link QueryNode} variant are immutable value types with structural
 * equality, that equivalence is simply {@link #equals(Object)}.
 *
 * @param nodes the ordered query nodes (never {@code null}; may be empty when the query is empty
 *     after normalization, per Req 7.4)
 */
public record StructuredQuery(List<QueryNode> nodes) {

    public StructuredQuery {
        Objects.requireNonNull(nodes, "nodes must not be null");
        nodes = List.copyOf(nodes);
    }

    /** An empty structured query (no query terms; Req 7.4). */
    public static StructuredQuery empty() {
        return new StructuredQuery(List.of());
    }
}
