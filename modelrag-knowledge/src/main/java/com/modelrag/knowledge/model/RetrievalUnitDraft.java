package com.modelrag.knowledge.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Input for persisting one retrieval projection. */
public record RetrievalUnitDraft(long datasetId, long documentId, long documentVersionId,
        long nodeId, long indexBuildId, RetrievalUnitType unitType, int ordinal, String titlePath,
        String content, String contentHash, int tokenCount, Map<String, Object> metadata) {
    public RetrievalUnitDraft {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
