package com.searchengine.common;

import java.util.Objects;

/**
 * Doc_ID: an opaque, monotonically-allocated unique identifier for a Document.
 *
 * <p>Stable for a Document's lifetime and unique across the whole index (Req 5.3).
 * {@code shardFor(docId)} is a pure function of this value.
 *
 * <p>Modeled as a wrapper over a {@code long} so it is a distinct type from other
 * numeric identifiers (compile-time safety) while remaining cheap to compare and hash.
 */
public final class DocId implements Comparable<DocId> {

    private final long value;

    private DocId(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("DocId must be non-negative, got: " + value);
        }
        this.value = value;
    }

    public static DocId of(long value) {
        return new DocId(value);
    }

    public long value() {
        return value;
    }

    @Override
    public int compareTo(DocId other) {
        return Long.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocId)) {
            return false;
        }
        return value == ((DocId) o).value;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return "DocId(" + value + ")";
    }
}
