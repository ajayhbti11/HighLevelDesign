package com.searchengine.crawler;

import com.searchengine.common.CanonicalUrl;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SeenSet: the Frontier's membership structure for deciding whether a Canonical_URL has
 * already been seen (design LLD "Key Data Structures" — {@code SeenSet = Bloom filter +
 * exact hash set}).
 *
 * <p>Membership is answered in two layers so that the Crawler never re-adds a URL that is
 * already queued or already fetched (Req 1.3), and so that canonical-equivalent URLs collapse
 * to a single entry (Req 1.6):
 *
 * <ol>
 *   <li>A <strong>Bloom filter</strong> gives a fast negative answer: if the filter reports
 *       absence, the URL is definitely not in the exact set and cannot have been added, so we
 *       can return {@code false} immediately without touching the exact set.</li>
 *   <li>An <strong>exact hash set</strong> confirms a positive Bloom answer. Because a Bloom
 *       filter can produce false positives but never false negatives, the exact set is
 *       consulted whenever the filter reports presence. This prevents a Bloom false positive
 *       from wrongly dropping a genuinely new URL (Req 1.3, 1.6).</li>
 * </ol>
 *
 * <p>In addition to its own two layers, {@code contains} consults a <strong>Content_Store
 * membership hook</strong>: a URL that has already been fetched (and therefore lives in the
 * Content_Store) is considered "seen" even if it was never added to this SeenSet, so the
 * Frontier will not re-enqueue it (Req 1.3).
 *
 * <p>Operations are keyed on {@link CanonicalUrl} values (reusing the canonicalization result
 * type), so canonical-equivalent inputs share a single membership entry.
 *
 * <p>This class is not thread-safe; the owning Frontier is responsible for synchronization.
 */
public final class SeenSet {

    /** Default Bloom filter size in bits (~1 million bits ≈ 128 KB). */
    private static final int DEFAULT_BIT_COUNT = 1 << 20;

    /** Default number of hash probes per key. */
    private static final int DEFAULT_HASH_COUNT = 7;

    /**
     * Content_Store membership hook. Given a Canonical_URL, returns {@code true} when a
     * document for that URL already exists in the Content_Store (i.e. it was already fetched).
     * Defaults to "nothing is in the Content_Store" when no hook is supplied.
     */
    private final Predicate<CanonicalUrl> contentStoreMembership;

    /** Bloom filter bit array; a set bit is a (possibly false-positive) "maybe present" signal. */
    private final long[] bits;
    private final int bitCount;
    private final int hashCount;

    /** Exact set of added Canonical_URLs; confirms Bloom positives and eliminates false drops. */
    private final Set<CanonicalUrl> exact = new HashSet<>();

    /**
     * Creates a SeenSet with default sizing and no Content_Store hook (nothing considered
     * already fetched).
     */
    public SeenSet() {
        this(null, DEFAULT_BIT_COUNT, DEFAULT_HASH_COUNT);
    }

    /**
     * Creates a SeenSet with default sizing and the given Content_Store membership hook.
     *
     * @param contentStoreMembership predicate that reports whether a Canonical_URL is already
     *                               present in the Content_Store; may be {@code null} to treat
     *                               the Content_Store as empty.
     */
    public SeenSet(Predicate<CanonicalUrl> contentStoreMembership) {
        this(contentStoreMembership, DEFAULT_BIT_COUNT, DEFAULT_HASH_COUNT);
    }

    /**
     * Creates a SeenSet with explicit Bloom filter sizing.
     *
     * @param contentStoreMembership Content_Store membership hook; may be {@code null}.
     * @param bitCount               number of bits in the Bloom filter; must be positive.
     * @param hashCount              number of hash probes per key; must be positive.
     */
    public SeenSet(Predicate<CanonicalUrl> contentStoreMembership, int bitCount, int hashCount) {
        if (bitCount <= 0) {
            throw new IllegalArgumentException("bitCount must be positive");
        }
        if (hashCount <= 0) {
            throw new IllegalArgumentException("hashCount must be positive");
        }
        this.contentStoreMembership =
                contentStoreMembership == null ? url -> false : contentStoreMembership;
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.bits = new long[(bitCount + 63) >>> 6];
    }

    /**
     * Records a valid Canonical_URL as seen. Invalid canonical URLs are never stored (there is
     * nothing meaningful to dedup on).
     *
     * @param url the Canonical_URL to mark seen; must be non-null.
     * @return {@code true} if this call added a new entry, {@code false} if it was already seen
     *         in the exact set (canonical-equivalent duplicate, Req 1.6).
     * @throws NullPointerException if {@code url} is null.
     */
    public boolean add(CanonicalUrl url) {
        Objects.requireNonNull(url, "url must not be null");
        if (!url.isValid()) {
            return false;
        }
        boolean added = exact.add(url);
        if (added) {
            setBits(url.value());
        }
        return added;
    }

    /**
     * Reports whether a Canonical_URL is already seen — either present in this SeenSet or
     * already fetched into the Content_Store (Req 1.3). Canonical-equivalent URLs share an
     * entry (Req 1.6).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Invalid canonical URLs are never "seen".</li>
     *   <li>If the Bloom filter reports absence, return {@code false} immediately (fast
     *       negative) after still consulting the Content_Store hook.</li>
     *   <li>Otherwise confirm against the exact set (guards against Bloom false positives).</li>
     *   <li>In all cases, a URL already in the Content_Store counts as seen.</li>
     * </ol>
     *
     * @param url the Canonical_URL to test; must be non-null.
     * @return {@code true} if the URL should be treated as already seen.
     * @throws NullPointerException if {@code url} is null.
     */
    public boolean contains(CanonicalUrl url) {
        Objects.requireNonNull(url, "url must not be null");
        if (!url.isValid()) {
            return false;
        }
        // Fast negative: if the Bloom filter says "definitely not present" in the exact set,
        // skip the exact-set lookup. The Content_Store hook is still authoritative for
        // already-fetched URLs (Req 1.3).
        if (!mightContain(url.value())) {
            return contentStoreMembership.test(url);
        }
        // Bloom reported "maybe": confirm with the exact set to avoid false-positive drops.
        if (exact.contains(url)) {
            return true;
        }
        return contentStoreMembership.test(url);
    }

    /** @return the number of distinct Canonical_URLs recorded in the exact set. */
    public int size() {
        return exact.size();
    }

    // --- Bloom filter internals -------------------------------------------------------------

    private void setBits(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a(data);
        long h2 = murmurMix(h1);
        for (int i = 0; i < hashCount; i++) {
            int index = bitIndex(h1, h2, i);
            bits[index >>> 6] |= (1L << (index & 63));
        }
    }

    private boolean mightContain(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a(data);
        long h2 = murmurMix(h1);
        for (int i = 0; i < hashCount; i++) {
            int index = bitIndex(h1, h2, i);
            if ((bits[index >>> 6] & (1L << (index & 63))) == 0L) {
                return false;
            }
        }
        return true;
    }

    /** Kirsch-Mitzenmacher double hashing: g_i(x) = h1 + i*h2, mapped into [0, bitCount). */
    private int bitIndex(long h1, long h2, int i) {
        long combined = h1 + (long) i * h2;
        // Force non-negative before modulo.
        int index = (int) Math.floorMod(combined, (long) bitCount);
        return index;
    }

    private static long fnv1a(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long murmurMix(long h) {
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
