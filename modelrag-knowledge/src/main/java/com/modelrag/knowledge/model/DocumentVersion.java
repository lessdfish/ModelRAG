package com.modelrag.knowledge.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable content snapshot; its version number is independent from the legacy index version. */
public record DocumentVersion(long id, long documentId, long versionNo,
        String sourceHash, String sourceObjectKey, String artifactObjectKey, String contentHash,
        String parserName, String parserVersion, DocumentParseStatus parseStatus,
        Map<String, Object> metadata, Instant createTime, Instant readyTime) {
    public DocumentVersion {
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
