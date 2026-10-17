package com.searchengine.index;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * VarintCodec: delta + LEB128 varint encoding for ascending integer sequences (design
 * data-structures table: "Delta-encoded ascending Doc_IDs + varint; positions delta-encoded
 * ascending", Req 5.1, 5.2).
 *
 * <p>Posting lists keep ascending Doc_IDs and ascending positions, which makes them compress
 * well: storing successive differences (deltas) yields small non-negative numbers, and
 * unsigned LEB128 varints store small numbers in fewer bytes. This class provides the
 * encode/decode primitives; the live index uses the plain in-memory {@link PostingList} /
 * {@link Segment} representations for now, and this codec documents and enables the storage
 * form the design specifies.
 *
 * <p>Precondition for {@link #encodeDeltas(List)}: values must be non-negative and ascending.
 */
public final class VarintCodec {

    private VarintCodec() {
        // static-only utility
    }

    /**
     * Delta-encodes an ascending, non-negative sequence and writes each delta as an unsigned
     * LEB128 varint.
     *
     * @param ascendingValues ascending, non-negative integers (e.g. positions or Doc_ID values)
     * @return the encoded bytes
     * @throws IllegalArgumentException if a value is negative or the sequence is not ascending
     */
    public static byte[] encodeDeltas(List<Integer> ascendingValues) {
        Objects.requireNonNull(ascendingValues, "ascendingValues must not be null");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long prev = 0;
        for (int value : ascendingValues) {
            if (value < 0) {
                throw new IllegalArgumentException("values must be non-negative, got: " + value);
            }
            long delta = value - prev;
            if (delta < 0) {
                throw new IllegalArgumentException("values must be ascending; found " + value + " after " + prev);
            }
            writeUnsignedVarint(out, delta);
            prev = value;
        }
        return out.toByteArray();
    }

    /**
     * Inverse of {@link #encodeDeltas(List)}: decodes delta-encoded varints back into the
     * original ascending sequence.
     */
    public static List<Integer> decodeDeltas(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded must not be null");
        List<Integer> values = new ArrayList<>();
        long running = 0;
        int i = 0;
        while (i < encoded.length) {
            long shift = 0;
            long delta = 0;
            while (true) {
                if (i >= encoded.length) {
                    throw new IllegalArgumentException("truncated varint in encoded input");
                }
                byte b = encoded[i++];
                delta |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            running += delta;
            values.add(Math.toIntExact(running));
        }
        return values;
    }

    private static void writeUnsignedVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) (v & 0x7F));
    }
}
