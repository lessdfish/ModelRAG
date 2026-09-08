package com.modelrag.knowledge.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable structural object belonging to one immutable document version. */
public record DocumentNode(long id, long datasetId, long documentId, long documentVersionId, Long parentId,
        NodeType nodeType, int depth, int ordinal, String title, String content, String contentHash,
        Integer pageFrom, Integer pageTo, Long charStart, Long charEnd, int tokenCount, boolean searchable,
        Map<String, Object> metadata, Instant createTime) {
    public DocumentNode {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
