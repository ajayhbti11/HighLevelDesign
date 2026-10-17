package com.searchengine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link VarintCodec} delta/varint encoding (design data-structures table). */
class VarintCodecTest {

    @Test
    void roundTripsAscendingPositions() {
        List<Integer> positions = List.of(0, 1, 5, 130, 130 + 300);
        byte[] encoded = VarintCodec.encodeDeltas(positions);
        assertEquals(positions, VarintCodec.decodeDeltas(encoded));
    }

    @Test
    void roundTripsEmptySequence() {
        byte[] encoded = VarintCodec.encodeDeltas(List.of());
        assertEquals(List.of(), VarintCodec.decodeDeltas(encoded));
    }

    @Test
    void smallDeltasUseFewerBytes() {
        // Consecutive values (delta 1) encode to one byte each.
        byte[] encoded = VarintCodec.encodeDeltas(List.of(1000, 1001, 1002, 1003));
        // First value delta=1000 needs 2 bytes; the following three deltas of 1 need 1 byte each.
        assertEquals(5, encoded.length);
    }

    @Test
    void rejectsDescendingSequence() {
        assertThrows(IllegalArgumentException.class, () -> VarintCodec.encodeDeltas(List.of(5, 3)));
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> VarintCodec.encodeDeltas(List.of(-1)));
    }
}
