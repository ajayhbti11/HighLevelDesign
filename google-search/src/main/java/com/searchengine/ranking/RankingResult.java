package com.searchengine.ranking;

import com.searchengine.common.RankedResult;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a ranking pass (design §G, Req 10).
 *
 * <p>Carries the ordered {@link RankedResult} list (score descending, Doc_ID ascending) and the
 * list of {@link ScoringFailure}s for any Documents excluded because their scoring threw
 * (Req 10.7). When the candidate set is empty, {@code ranked} is empty and no error is raised
 * (Req 10.6).
 *
 * @param ranked   the ordered results; a strict total order (Req 10.2, 10.3)
 * @param failures the recorded per-Document scoring failures (Req 10.7)
 */
public record RankingResult(List<RankedResult> ranked, List<ScoringFailure> failures) {

    public RankingResult {
        ranked = List.copyOf(Objects.requireNonNull(ranked, "ranked must not be null"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures must not be null"));
    }
}
