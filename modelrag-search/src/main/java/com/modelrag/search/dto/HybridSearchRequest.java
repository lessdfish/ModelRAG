package com.modelrag.search.dto;

import java.util.Map;

public record HybridSearchRequest(long datasetId, String query, int topK, double threshold,
        Map<Long, Long> activeIndexVersions) {
    public HybridSearchRequest(long datasetId, String query, int topK) {
        this(datasetId, query, topK, 0, Map.of());
    }

    public HybridSearchRequest(long datasetId, String query, int topK, double threshold) {
        this(datasetId, query, topK, threshold, Map.of());
    }

    public HybridSearchRequest withActiveIndexVersions(Map<Long, Long> versions) {
        return new HybridSearchRequest(datasetId, query, topK, threshold,
                versions == null ? Map.of() : Map.copyOf(versions));
    }
}
