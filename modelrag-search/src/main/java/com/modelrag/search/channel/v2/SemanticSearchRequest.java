package com.modelrag.search.channel.v2;

/** Bounded vector-store request for one rewritten V2 query. */
public record SemanticSearchRequest(long datasetId, float[] queryEmbedding,
        String embeddingProfile, int limit) {
    public static final int MAX_LIMIT = 500;

    public SemanticSearchRequest {
        if (datasetId <= 0) throw new IllegalArgumentException("datasetId must be positive");
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new IllegalArgumentException("query embedding must not be empty");
        }
        if (embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new IllegalArgumentException("embedding profile must not be blank");
        }
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit is out of bounds");
        queryEmbedding = queryEmbedding.clone();
    }

    @Override
    public float[] queryEmbedding() {
        return queryEmbedding.clone();
    }
}
