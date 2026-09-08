package com.modelrag.knowledge.parser;

import com.modelrag.knowledge.model.NodeEdgeType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit non-tree relation emitted by a structured parser. */
public record ParsedEdge(String fromLocalId, String toLocalId, NodeEdgeType edgeType,
        Map<String, Object> metadata) {
    public ParsedEdge {
        if (fromLocalId == null || fromLocalId.isBlank() || toLocalId == null || toLocalId.isBlank()
                || edgeType == null || fromLocalId.equals(toLocalId)) {
            throw new IllegalArgumentException("解析边参数无效");
        }
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
