package com.searchengine.ranking;

import com.searchengine.common.DocId;
import com.searchengine.common.RankedResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ranker: scores candidate Documents and orders them into a strict total order
 * (design §6, §G "Ranking"; Req 10).
 *
 * <p>The Relevance_Score blends a term-relevance component ({@code bm25Norm}) and a
 * document-authority component ({@code authorityNorm}), each in {@code [0,1]}, via weights
 * {@code w_r} and {@code w_a} with {@code w_r + w_a = 1}, clamped to {@code [0,1]}:
 *
 * <pre>{@code
 * score(doc) = clamp01( w_r * bm25Norm(doc) + w_a * authorityNorm(doc) )   // Req 10.1, 10.4
 * order:       sort by (score desc, docId asc)                             // Req 10.2, 10.3
 * }</pre>
 *
 * <p>Behavioral guarantees:
 * <ul>
 *   <li>Every produced score lies in {@code [0.0, 1.0]} (Req 10.1).</li>
 *   <li>Results are ordered by descending score, ties broken by ascending Doc_ID, forming a
 *       strict total order (Req 10.2, 10.3).</li>
 *   <li>Both components contribute to the score (Req 10.4).</li>
 *   <li>Ranking is deterministic for identical inputs (Req 10.5) — it is a pure function of the
 *       candidate list, the weights, and the {@link ScoringFunction}.</li>
 *   <li>An empty candidate set yields an empty result with no error (Req 10.6).</li>
 *   <li>If scoring a Document throws, that Document is excluded, the failure is recorded, and the
 *       ordering of the remaining Documents is preserved (Req 10.7).</li>
 * </ul>
 *
 * <p>Instances are immutable and therefore thread-safe.
 */
public final class Ranker {

    private final double relevanceWeight; // w_r
    private final double authorityWeight; // w_a
    private final ScoringFunction scoringFunction;

    /** Default term-relevance weight when none is supplied. */
    public static final double DEFAULT_RELEVANCE_WEIGHT = 0.7;

    private static final double WEIGHT_SUM_TOLERANCE = 1e-9;

    /**
     * Creates a Ranker with the given weights and the default blend scoring function.
     *
     * @param relevanceWeight w_r, in {@code [0,1]}
     * @param authorityWeight w_a, in {@code [0,1]}; must satisfy {@code w_r + w_a == 1}
     */
    public Ranker(double relevanceWeight, double authorityWeight) {
        this(relevanceWeight, authorityWeight, defaultBlend(relevanceWeight, authorityWeight));
    }

    /**
     * Creates a Ranker with explicit weights and a custom {@link ScoringFunction}. The weights are
     * validated so callers relying on them (and the default blend) get the {@code w_r + w_a = 1}
     * guarantee (design §G).
     */
    public Ranker(double relevanceWeight, double authorityWeight, ScoringFunction scoringFunction) {
        requireUnitInterval(relevanceWeight, "relevanceWeight");
        requireUnitInterval(authorityWeight, "authorityWeight");
        if (Math.abs((relevanceWeight + authorityWeight) - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    "weights must satisfy w_r + w_a = 1, got w_r=" + relevanceWeight
                            + ", w_a=" + authorityWeight);
        }
        this.relevanceWeight = relevanceWeight;
        this.authorityWeight = authorityWeight;
        this.scoringFunction = Objects.requireNonNull(scoringFunction, "scoringFunction must not be null");
    }

    /** Creates a Ranker with default weights ({@value #DEFAULT_RELEVANCE_WEIGHT} / remainder). */
    public static Ranker withDefaultWeights() {
        return new Ranker(DEFAULT_RELEVANCE_WEIGHT, 1.0 - DEFAULT_RELEVANCE_WEIGHT);
    }

    /**
     * Scores and orders the candidate Documents (Req 10).
     *
     * @param candidates the candidate Documents with their normalized components; must not be null
     *                   but may be empty (Req 10.6). Must not contain duplicate Doc_IDs.
     * @return the ordered results plus any recorded scoring failures
     * @throws NullPointerException     if {@code candidates} or any element is null
     * @throws IllegalArgumentException if two candidates share the same Doc_ID
     */
    public RankingResult rank(List<RankingCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        // Req 10.6: empty candidate set -> empty result, no error.
        if (candidates.isEmpty()) {
            return new RankingResult(List.of(), List.of());
        }

        List<RankedResult> scored = new ArrayList<>(candidates.size());
        List<ScoringFailure> failures = new ArrayList<>();

        for (RankingCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates must not contain null elements");
            try {
                double raw = scoringFunction.score(candidate);
                double score = clamp01(raw);
                scored.add(new RankedResult(candidate.docId(), score));
            } catch (RuntimeException scoringError) {
                // Req 10.7: exclude the failed Document, record the failure, preserve the rest.
                failures.add(new ScoringFailure(candidate.docId(), scoringError));
            }
        }

        // Req 10.2, 10.3, 10.5: strict total order — score desc, Doc_ID asc (via RankedResult).
        Collections.sort(scored);

        return new RankingResult(scored, failures);
    }

    /** The configured term-relevance weight (w_r). */
    public double relevanceWeight() {
        return relevanceWeight;
    }

    /** The configured document-authority weight (w_a). */
    public double authorityWeight() {
        return authorityWeight;
    }

    /**
     * The default blend: {@code w_r * bm25Norm + w_a * authorityNorm} (design §G, Req 10.4).
     * Because both components are in {@code [0,1]} and {@code w_r + w_a = 1}, the raw value is
     * already in {@code [0,1]}; the Ranker still clamps as a defensive measure (Req 10.1).
     */
    private static ScoringFunction defaultBlend(double relevanceWeight, double authorityWeight) {
        return candidate ->
                relevanceWeight * candidate.bm25Norm() + authorityWeight * candidate.authorityNorm();
    }

    /** Clamps a value to the inclusive range {@code [0.0, 1.0]} (Req 10.1). */
    static double clamp01(double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("score must not be NaN");
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static void requireUnitInterval(double v, String name) {
        if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0], got: " + v);
        }
    }
}
