package com.modelrag.inference.rerank;

import java.util.List;

/** Wire response from the remote reranking endpoint. */
public record RerankResponse(List<RerankScore> scores) {
    public RerankResponse {
        scores = scores == null ? List.of() : List.copyOf(scores);
    }

    /** Compatibility overload for callers that included an echoed model in early drafts. */
    public RerankResponse(String ignoredModel, List<RerankScore> scores) {
        this(scores);
    }
}
