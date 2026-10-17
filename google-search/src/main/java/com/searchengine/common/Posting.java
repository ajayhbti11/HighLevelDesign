package com.searchengine.common;

import java.util.List;
import java.util.Objects;

/**
 * Posting: a single entry in a term's Posting_List (design §4, Req 5.1, 5.2).
 *
 * <p>Records the Doc_ID that contains the term and the ascending term positions of the term
 * within that document. Positions enable phrase / positional matching (Req 8.2).
 *
 * @param docId     the Document containing the term
 * @param positions ascending term positions of the term within the document (Req 5.2)
 */
public record Posting(DocId docId, List<Integer> positions) {

    public Posting {
        Objects.requireNonNull(docId, "docId must not be null");
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
