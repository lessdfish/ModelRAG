package com.modelrag.knowledge.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable input for creating one document-structure node. */
public record DocumentNodeDraft(long datasetId, long documentId, long documentVersionId, Long parentId,
        NodeType nodeType, int depth, int ordinal, String title, String content, String contentHash,
        Integer pageFrom, Integer pageTo, Long charStart, Long charEnd, int tokenCount, boolean searchable,
        Map<String, Object> metadata) {
    public DocumentNodeDraft {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
