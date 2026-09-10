package com.modelrag.inference.rerank;

import java.util.List;

/** Wire request for the bounded reranking endpoint. */
public record RerankRequest(String model, String query, List<RerankDocument> documents) {
    public RerankRequest {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("rerank model is required");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("rerank query is required");
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
