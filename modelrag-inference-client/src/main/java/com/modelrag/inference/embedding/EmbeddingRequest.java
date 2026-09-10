package com.modelrag.inference.embedding;

import java.util.List;

public record EmbeddingRequest(String model, int dimensions, List<String> texts) {
    public EmbeddingRequest {
        texts = texts == null ? List.of() : List.copyOf(texts);
    }
}
