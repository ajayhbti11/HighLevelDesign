package com.searchengine.index;

import com.searchengine.common.DocId;

import java.util.Objects;

/**
 * IndexResult: the outcome of an {@link Indexer} operation ({@code index} / {@code reindex} /
 * {@code remove}) for a single Doc_ID (design §4 "IndexResult carries success | revertedOnFailure
 * (Req 5.6, transactional per Doc_ID)").
 *
 * <p>An operation is transactional per Doc_ID: it either fully applies (success) or, if it fails
 * after a partial posting-list update, reverts to the prior state and records the failure
 * (Req 5.6). {@link #kind()} distinguishes those outcomes.
 */
public final class IndexResult {

    /** The kind of outcome an {@link Indexer} operation produced. */
    public enum Kind {
        /** The operation applied fully and atomically. */
        SUCCESS,
        /** The operation failed after a partial update and was reverted to the prior state (Req 5.6). */
        REVERTED_ON_FAILURE,
        /** The target Doc_ID was not present (e.g. removing an unknown Doc_ID). */
        NOT_FOUND
    }

    private final Kind kind;
    private final DocId docId;
    private final String failureCause;

    private IndexResult(Kind kind, DocId docId, String failureCause) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.docId = docId;
        this.failureCause = failureCause;
    }

    /** A successful operation for {@code docId}. */
    public static IndexResult success(DocId docId) {
        return new IndexResult(Kind.SUCCESS, Objects.requireNonNull(docId, "docId"), null);
    }

    /** A failed operation that was reverted to the prior state, recording {@code cause} (Req 5.6). */
    public static IndexResult revertedOnFailure(DocId docId, String cause) {
        return new IndexResult(Kind.REVERTED_ON_FAILURE, docId, Objects.requireNonNull(cause, "cause"));
    }

    /** The target Doc_ID was not present. */
    public static IndexResult notFound(DocId docId) {
        return new IndexResult(Kind.NOT_FOUND, docId, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The Doc_ID the operation targeted, or {@code null} when unknown. */
    public DocId docId() {
        return docId;
    }

    public boolean isSuccess() {
        return kind == Kind.SUCCESS;
    }

    public boolean isRevertedOnFailure() {
        return kind == Kind.REVERTED_ON_FAILURE;
    }

    /** The recorded failure cause when {@link #isRevertedOnFailure()}, otherwise {@code null}. */
    public String failureCause() {
        return failureCause;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("IndexResult{").append(kind).append(", docId=").append(docId);
        if (failureCause != null) {
            sb.append(", cause=").append(failureCause);
        }
        return sb.append('}').toString();
    }
}
