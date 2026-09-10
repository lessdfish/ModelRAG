package com.modelrag.inference.rerank;

/** Stable candidate identity and text sent to the remote reranker. */
public record RerankDocument(String id, String text) {
    public RerankDocument {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("rerank document id is required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("rerank document text is required");
        id = id.trim();
        text = text.trim();
    }
}
