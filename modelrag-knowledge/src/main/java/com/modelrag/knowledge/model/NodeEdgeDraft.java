package com.modelrag.knowledge.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable input for creating a non-tree node relation. */
public record NodeEdgeDraft(long fromNodeId, long toNodeId, NodeEdgeType edgeType,
        Map<String, Object> metadata) {
    public NodeEdgeDraft {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
