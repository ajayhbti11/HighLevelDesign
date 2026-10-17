package com.searchengine.serving;

import com.searchengine.common.DocId;

/**
 * Resolves the display {@link DocumentMetadata} for a ranked Doc_ID during Result_Entry assembly
 * (design §6, §H; Req 11.2, 11.7).
 *
 * <p>The {@link Paginator} calls this only for the Doc_IDs on the requested page, so implementations
 * need not pre-materialize metadata for the entire ranked list. A resolver must return non-null
 * metadata (with a non-null Canonical_URL) for every Doc_ID present in the ranked list handed to
 * the Paginator; the title and snippet fields may be null/empty to trigger substitution (Req 11.7).
 */
@FunctionalInterface
public interface MetadataResolver {

    /**
     * Returns the display metadata for {@code docId}.
     *
     * @param docId the ranked Document identifier (never null)
     * @return the Document's display metadata (must not be null; its Canonical_URL must not be null)
     */
    DocumentMetadata resolve(DocId docId);
}
