package com.modelrag.search.dto;

/** Internal V2 retrieval request; it is not a public user-facing API. */
public record RetrievalV2Request(long datasetId, String query, int topK, double threshold,
        String embeddingProfile) {
    public static final String DEFAULT_EMBEDDING_PROFILE = "qwen3-v1";
    public static final int MAX_TOP_K = 100;

    public RetrievalV2Request(long datasetId, String query, int topK) {
        this(datasetId, query, topK, 0, DEFAULT_EMBEDDING_PROFILE);
    }

    public RetrievalV2Request(long datasetId, String query, int topK, double threshold) {
        this(datasetId, query, topK, threshold, DEFAULT_EMBEDDING_PROFILE);
    }

    public RetrievalV2Request {
        if (datasetId <= 0) throw new IllegalArgumentException("datasetId must be positive");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (topK < 1 || topK > MAX_TOP_K) throw new IllegalArgumentException("topK is out of bounds");
        if (Double.isNaN(threshold) || Double.isInfinite(threshold)) {
            throw new IllegalArgumentException("threshold is invalid");
        }
        embeddingProfile = embeddingProfile == null || embeddingProfile.isBlank()
                ? DEFAULT_EMBEDDING_PROFILE : embeddingProfile.trim();
    }
}
