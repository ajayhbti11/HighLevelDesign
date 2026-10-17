package com.searchengine.query;

import java.util.Optional;

/**
 * SupportedOp: the closed set of operators the Query_Processor recognizes (design §5, Req 7.3).
 *
 * <p>Each operator has a single canonical surface {@link #keyword()} used both when interpreting
 * query text and when serializing a {@link StructuredQuery} back to text. Any operator-looking
 * token whose surface form is not one of these is <strong>not</strong> part of the grammar and is
 * therefore treated as a literal term (Req 7.7).
 *
 * <p>The grammar is intentionally small and unambiguous:
 * <ul>
 *   <li>{@code AND} — both operands are required (this is also the implicit default between bare
 *       terms; see {@link QueryProcessor}).</li>
 *   <li>{@code OR} — either operand may match.</li>
 *   <li>{@code NOT} — the following operand must not match.</li>
 * </ul>
 *
 * <p>Operator keywords are matched case-insensitively against the normalized token surface; the
 * canonical serialized form is upper case.
 */
public enum SupportedOp {
    AND("and"),
    OR("or"),
    NOT("not");

    private final String keyword;

    SupportedOp(String keyword) {
        this.keyword = keyword;
    }

    /** The canonical lower-case surface keyword for this operator. */
    public String keyword() {
        return keyword;
    }

    /**
     * Resolves a normalized token surface to a supported operator, if any.
     *
     * @param tokenText a normalized (already case-folded) token surface
     * @return the matching operator, or {@link Optional#empty()} when the surface is not a
     *     supported operator keyword (Req 7.7: unknown operators are treated as literals)
     */
    public static Optional<SupportedOp> fromToken(String tokenText) {
        if (tokenText == null) {
            return Optional.empty();
        }
        for (SupportedOp op : values()) {
            if (op.keyword.equals(tokenText)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }
}
