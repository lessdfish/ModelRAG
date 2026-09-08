package com.modelrag.search.dto;

import com.modelrag.knowledge.model.RetrievalUnitType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** V2 retrieval result. Its identity is the immutable retrieval-unit ID. */
public record RetrievalCandidate(long datasetId, long retrievalUnitId, long nodeId,
        long documentId, long documentVersionId, long indexBuildId, RetrievalUnitType unitType,
        String titlePath, String content, double score, RetrievalChannel channel, int rank,
        Map<String, Object> metadata) {
    public RetrievalCandidate {
        if (datasetId <= 0 || retrievalUnitId <= 0 || nodeId <= 0 || documentId <= 0
                || documentVersionId <= 0 || indexBuildId <= 0) {
            throw new IllegalArgumentException("V2 retrieval candidate identity is invalid");
        }
        if (unitType == null || channel == null || rank < 0) {
            throw new IllegalArgumentException("V2 retrieval candidate fields are invalid");
        }
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
