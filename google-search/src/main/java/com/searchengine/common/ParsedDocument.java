package com.searchengine.common;

import java.util.List;
import java.util.Objects;

/**
 * ParsedDocument: the structured output of the Parser (design §2, Req 4.1-4.6).
 *
 * @param docId            the assigned Doc_ID (may be null before indexing assigns one)
 * @param canonicalUrl     the Canonical_URL of this document
 * @param title            the extracted title metadata, or null when absent (Req 4.2, 11.7)
 * @param normalizedTokens the human-readable text as normalized tokens via shared normalize() (Req 4.1, 4.3)
 * @param outboundLinks    all extracted outbound links (Req 4.2)
 * @param contentHash      hash of the normalized content used for duplicate detection (Req 4.6)
 * @param status           the parse status (Req 4.4, 4.5)
 */
public record ParsedDocument(
        DocId docId,
        String canonicalUrl,
        String title,
        List<Token> normalizedTokens,
        List<String> outboundLinks,
        String contentHash,
        ParseStatus status) {

    public ParsedDocument {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        Objects.requireNonNull(status, "status must not be null");
        // Defensive copies keep the record immutable.
        normalizedTokens = normalizedTokens == null ? List.of() : List.copyOf(normalizedTokens);
        outboundLinks = outboundLinks == null ? List.of() : List.copyOf(outboundLinks);
    }
}
