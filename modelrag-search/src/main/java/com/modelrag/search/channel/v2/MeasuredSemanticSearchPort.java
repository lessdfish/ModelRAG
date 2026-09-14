package com.modelrag.search.channel.v2;

import com.modelrag.search.dto.RetrievalCandidate;
import java.util.List;

/** Optional semantic port contract exposing bounded ANN refill diagnostics. */
public interface MeasuredSemanticSearchPort extends SemanticSearchPort {
    MeasuredResult searchMeasured(SemanticSearchRequest request);

    record MeasuredResult(List<RetrievalCandidate> candidates, int refillRounds,
            int candidateBudget, boolean refillExhausted) {
        public MeasuredResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
