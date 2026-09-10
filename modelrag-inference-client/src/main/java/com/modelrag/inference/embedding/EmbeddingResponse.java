package com.modelrag.inference.embedding;

import java.util.List;

public record EmbeddingResponse(String model, int dimensions, List<float[]> embeddings) {
    public EmbeddingResponse {
        embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
    }
}
