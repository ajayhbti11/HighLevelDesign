package com.searchengine.query;

import com.searchengine.common.Normalizer;
import com.searchengine.common.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Query_Processor: parses raw user query text into a {@link StructuredQuery}, serializes a
 * StructuredQuery back to text, and detects empty queries (design §5, Requirement 7).
 *
 * <p>Parsing pipeline (order matters):
 * <ol>
 *   <li><b>Truncate first (Req 7.8).</b> If the raw query is longer than {@link #MAX_QUERY_LENGTH}
 *       characters, only the first {@code MAX_QUERY_LENGTH} characters are considered. Truncation
 *       happens before any tokenization.</li>
 *   <li><b>Segment on quotes (Req 7.2, 7.6).</b> The truncated text is scanned character by
 *       character. A double-quote opens a phrase; the next double-quote closes it. Text outside
 *       quotes is free text. An <em>unbalanced</em> (never-closed) quote does not reject the
 *       query: the characters after the dangling quote are parsed as ordinary free text (Req 7.6),
 *       so the query is always parseable.</li>
 *   <li><b>Normalize (Req 7.1).</b> Each free-text run and each quoted run is passed through the
 *       shared {@link Normalizer#normalize(String)} so that query terms are byte-identical to the
 *       index terms produced by the Parser.</li>
 *   <li><b>Classify tokens (Req 7.3, 7.7).</b> A normalized free-text token whose surface matches a
 *       {@link SupportedOp} keyword becomes an {@link QueryNode.Operator}; every other free-text
 *       token becomes a {@link QueryNode.Term}. An operator-looking token that is not in the
 *       grammar is simply a literal {@link QueryNode.Term} (Req 7.7) — it never matches a
 *       {@code SupportedOp} keyword, so no special handling is required.</li>
 *   <li><b>Build phrase nodes (Req 7.2).</b> Each quoted run normalizes to an ordered list of
 *       tokens which becomes a single {@link QueryNode.Phrase}. A quoted run that normalizes to a
 *       single token is preserved as a one-token phrase so the round-trip (Req 7.5) is stable and
 *       the phrase intent is retained.</li>
 * </ol>
 *
 * <p>All methods are pure and deterministic; the class is stateless.
 */
public final class QueryProcessor {

    /** Req 7.8: normalized-length cap applied before tokenization. */
    public static final int MAX_QUERY_LENGTH = 2048;

    private static final char QUOTE = '"';

    /**
     * Parses raw query text into a {@link StructuredQuery} (Req 7.1, 7.2, 7.3, 7.6, 7.7, 7.8).
     *
     * @param rawQuery the raw query text; must not be {@code null}
     * @return the parsed structured query (possibly {@link StructuredQuery#empty()} when the query
     *     is empty after normalization, Req 7.4)
     */
    public StructuredQuery parse(String rawQuery) {
        Objects.requireNonNull(rawQuery, "rawQuery must not be null");

        // Req 7.8: truncate to the first MAX_QUERY_LENGTH characters before any tokenization.
        String truncated = truncate(rawQuery);

        List<QueryNode> nodes = new ArrayList<>();
        StringBuilder freeRun = new StringBuilder();

        int length = truncated.length();
        int i = 0;
        while (i < length) {
            char c = truncated.charAt(i);
            if (c == QUOTE) {
                // A quote begins a phrase. First flush any pending free text before it.
                flushFreeText(freeRun, nodes);

                int close = truncated.indexOf(QUOTE, i + 1);
                if (close < 0) {
                    // Req 7.6: unbalanced quote. The unmatched quote is dropped as a boundary and
                    // the remainder is parsed as ordinary free text; parsing continues, the query
                    // is not rejected.
                    freeRun.append(truncated, i + 1, length);
                    i = length;
                } else {
                    // Balanced quote: the enclosed text is a phrase (Req 7.2).
                    String inner = truncated.substring(i + 1, close);
                    addPhrase(inner, nodes);
                    i = close + 1;
                }
            } else {
                freeRun.append(c);
                i++;
            }
        }
        flushFreeText(freeRun, nodes);

        return new StructuredQuery(nodes);
    }

    /**
     * Serializes a {@link StructuredQuery} back to query text such that re-parsing the result
     * yields an equivalent StructuredQuery (Req 7.5).
     *
     * <p>The chosen textual form is canonical and round-trip stable:
     * <ul>
     *   <li>a {@link QueryNode.Term} serializes to its token;</li>
     *   <li>a {@link QueryNode.Phrase} serializes to its tokens joined by a single space, wrapped
     *       in double quotes;</li>
     *   <li>a {@link QueryNode.Operator} serializes to its upper-case keyword followed by its
     *       serialized operands.</li>
     * </ul>
     * Nodes are separated by a single space.
     *
     * @param query the structured query; must not be {@code null}
     * @return a query string that parses to an equivalent StructuredQuery
     */
    public String serialize(StructuredQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder out = new StringBuilder();
        for (QueryNode node : query.nodes()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            serializeNode(node, out);
        }
        return out.toString();
    }

    /**
     * Detects an empty query — one that has no query nodes after normalization (Req 7.4).
     *
     * @param query the structured query; must not be {@code null}
     * @return {@code true} iff the query carries no terms, phrases, or operators
     */
    public boolean isEmpty(StructuredQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return query.nodes().isEmpty();
    }

    // --- internals ---------------------------------------------------------------------------

    private static String truncate(String rawQuery) {
        if (rawQuery.length() <= MAX_QUERY_LENGTH) {
            return rawQuery;
        }
        return rawQuery.substring(0, MAX_QUERY_LENGTH);
    }

    /**
     * Normalizes accumulated free text and appends a Term or Operator node for each resulting
     * token, then clears the buffer. Empty/whitespace-only free text contributes nothing.
     */
    private static void flushFreeText(StringBuilder freeRun, List<QueryNode> nodes) {
        if (freeRun.length() == 0) {
            return;
        }
        for (Token token : Normalizer.normalize(freeRun.toString())) {
            String text = token.text();
            Optional<SupportedOp> op = SupportedOp.fromToken(text);
            if (op.isPresent()) {
                // Req 7.3: supported operator interpreted per grammar. Operands are the nodes that
                // follow in query order; the operand tree is kept flat here (ordered nodes), which
                // is sufficient for the round-trip equivalence contract (Req 7.5).
                nodes.add(new QueryNode.Operator(op.get(), List.of()));
            } else {
                // Bare word, or a literal produced from an unsupported operator (Req 7.7).
                nodes.add(new QueryNode.Term(text));
            }
        }
        freeRun.setLength(0);
    }

    /**
     * Normalizes the interior of a quoted run into an ordered phrase node (Req 7.2). A quoted run
     * that normalizes to no tokens (for example, {@code ""} or {@code " "}) contributes nothing.
     */
    private static void addPhrase(String inner, List<QueryNode> nodes) {
        List<Token> tokens = Normalizer.normalize(inner);
        if (tokens.isEmpty()) {
            return;
        }
        List<String> phraseTokens = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            phraseTokens.add(token.text());
        }
        nodes.add(new QueryNode.Phrase(phraseTokens));
    }

    private static void serializeNode(QueryNode node, StringBuilder out) {
        if (node instanceof QueryNode.Term term) {
            out.append(term.token());
        } else if (node instanceof QueryNode.Phrase phrase) {
            out.append(QUOTE);
            out.append(String.join(" ", phrase.tokens()));
            out.append(QUOTE);
        } else if (node instanceof QueryNode.Operator operator) {
            out.append(operator.op().keyword().toUpperCase(java.util.Locale.ROOT));
            for (QueryNode operand : operator.operands()) {
                out.append(' ');
                serializeNode(operand, out);
            }
        } else {
            throw new IllegalStateException("unknown QueryNode variant: " + node);
        }
    }
}
