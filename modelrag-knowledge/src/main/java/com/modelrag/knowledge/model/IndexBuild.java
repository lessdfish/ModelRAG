package com.modelrag.knowledge.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable physical V2 retrieval build; independent from content and legacy index versions. */
public record IndexBuild(long id, long datasetId, long documentId, long documentVersionId, long buildNo,
        IndexBuildState state, String embeddingProfile, String rerankProfile, long nodeCount, long unitCount,
        long vectorCount, long lexicalCount, String errorMsg, Map<String, Object> metadata,
        Instant createTime, Instant startTime, Instant readyTime, Instant activeTime, Instant failedTime) {
    public IndexBuild {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
