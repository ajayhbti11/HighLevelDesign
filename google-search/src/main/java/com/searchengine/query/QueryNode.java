package com.searchengine.query;

import java.util.List;
import java.util.Objects;

/**
 * QueryNode: one node of a parsed {@link StructuredQuery} tree (design §5, Req 7.2, 7.3).
 *
 * <p>A query is an ordered sequence of nodes; each node is exactly one of three shapes:
 * <ul>
 *   <li>{@link Term} — a single normalized token (a bare word, or a literal produced from an
 *       unbalanced quote or an unsupported operator; Req 7.6, 7.7).</li>
 *   <li>{@link Phrase} — an ordered sequence of tokens that must appear at consecutive positions
 *       (Req 7.2).</li>
 *   <li>{@link Operator} — a supported operator applied to ordered operand nodes (Req 7.3).</li>
 * </ul>
 *
 * <p>Nodes are immutable value types; equality is structural, which is what makes the round-trip
 * equivalence check in Req 7.5 well-defined (two structured queries are equivalent when they hold
 * the same tokens, phrases, and operators in the same order).
 */
public sealed interface QueryNode permits QueryNode.Term, QueryNode.Phrase, QueryNode.Operator {

    /**
     * A single normalized term token.
     *
     * @param token the normalized token text (never {@code null} or empty)
     */
    record Term(String token) implements QueryNode {
        public Term {
            Objects.requireNonNull(token, "token must not be null");
            if (token.isEmpty()) {
                throw new IllegalArgumentException("term token must not be empty");
            }
        }
    }

    /**
     * An ordered phrase constraint: {@code tokens} must appear in the given order at consecutive
     * positions with no intervening tokens (Req 7.2; matching is enforced later, Req 8.2).
     *
     * @param tokens the ordered, non-empty list of normalized tokens
     */
    record Phrase(List<String> tokens) implements QueryNode {
        public Phrase {
            Objects.requireNonNull(tokens, "tokens must not be null");
            tokens = List.copyOf(tokens);
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("phrase must contain at least one token");
            }
            for (String t : tokens) {
                if (t == null || t.isEmpty()) {
                    throw new IllegalArgumentException("phrase tokens must be non-empty");
                }
            }
        }
    }

    /**
     * A supported operator applied to ordered operands (Req 7.3).
     *
     * @param op       the supported operator
     * @param operands the ordered operand nodes
     */
    record Operator(SupportedOp op, List<QueryNode> operands) implements QueryNode {
        public Operator {
            Objects.requireNonNull(op, "op must not be null");
            Objects.requireNonNull(operands, "operands must not be null");
            operands = List.copyOf(operands);
        }
    }
}
