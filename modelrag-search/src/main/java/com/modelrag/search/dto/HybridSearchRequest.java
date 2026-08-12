package com.modelrag.search.dto;

public record HybridSearchRequest(long datasetId, String query, int topK, double threshold) {
    public HybridSearchRequest(long datasetId, String query, int topK) {
        this(datasetId, query, topK, 0);
    }
}
