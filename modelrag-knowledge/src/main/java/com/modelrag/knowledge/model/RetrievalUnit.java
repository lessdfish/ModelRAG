package com.modelrag.knowledge.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable retrieval projection of a document node for one V2 index build. */
public record RetrievalUnit(long id, long datasetId, long documentId, long documentVersionId,
        long nodeId, long indexBuildId, RetrievalUnitType unitType, int ordinal, String titlePath,
        String content, String contentHash, int tokenCount, Map<String, Object> metadata,
        Instant createTime) {
    public RetrievalUnit {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
