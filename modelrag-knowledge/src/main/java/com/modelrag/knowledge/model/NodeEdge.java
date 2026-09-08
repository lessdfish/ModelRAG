package com.modelrag.knowledge.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable non-tree relation between two document nodes. */
public record NodeEdge(long id, long fromNodeId, long toNodeId, NodeEdgeType edgeType,
        Map<String, Object> metadata, Instant createTime) {
    public NodeEdge {
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
